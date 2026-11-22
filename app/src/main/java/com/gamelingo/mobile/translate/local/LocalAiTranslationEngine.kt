package com.gamelingo.mobile.translate.local

import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline-first local AI engine: a downloadable Marian EN→AR model (int8 ONNX)
 * behind the shared [TranslationEngine] interface, plus the Egyptian dictionary
 * pipeline that turns its MSA output into natural Egyptian game Arabic.
 *
 * Guarantees (brief 6, phase 3):
 * - Zero network during translation; the model loads once from verified local
 *   files and stays warm for the session.
 * - Never touches the main thread: all inference runs on [Dispatchers.Default]
 *   behind a single mutex (sessions are not thread-safe; one instance only).
 * - Cancellation is checked at every decode step, so a cancelled or timed-out
 *   request frees the queue immediately instead of stalling it.
 * - Refuses to run when the model is not installed/verified: the caller gets a
 *   visible [ModelNotInstalledException], never a fake "local works" claim.
 */
@Singleton
class LocalAiTranslationEngine @Inject constructor(
    private val modelManager: LocalModelManager,
    private val dictionary: EgyptianDictionaryHolder,
) : LocalAiCandidate {

    /** The model is absent or failed verification; never swallow this silently. */
    class ModelNotInstalledException : Exception("local_model_not_installed")

    override val engineId: String = ENGINE_ID

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var runner: OrtOnnxRunner? = null

    /** Runners whose close was requested while an inference still used them. */
    private val pendingClose = java.util.concurrent.ConcurrentLinkedQueue<OrtOnnxRunner>()

    init {
        // Deleting the model must first release its sessions.
        modelManager.addReleaseListener(LocalModelManager.MODEL_MARIAN) { close() }
    }

    @Volatile
    private var tokenizer: SentencePieceTokenizer? = null

    /** Whether a verified model is present and loadable right now. */
    override fun isAvailable(): Boolean = modelManager.isInstalled(LocalModelManager.MODEL_MARIAN)

    /**
     * Loads tokenizer + sessions once. Idempotent; safe to call repeatedly.
     * Throws [ModelNotInstalledException] when no verified model exists.
     */
    override suspend fun ensureReady() {
        if (runner != null && tokenizer != null) return
        loadMutex.withLock {
            if (runner != null && tokenizer != null) return
            val dir = modelManager.modelDir(LocalModelManager.MODEL_MARIAN)
                ?: throw ModelNotInstalledException()
            val tokenizerFile = modelManager.modelFile(LocalModelManager.MODEL_MARIAN, TOKENIZER_FILE)
                ?: throw ModelNotInstalledException()
            // Throwable containment: native init failures (linkage, OOM) surface
            // as Errors; wrap them so the chain degrades visibly to the next
            // engine instead of letting the process die.
            try {
                withContext(Dispatchers.IO) {
                    tokenizer = SentencePieceTokenizer.load(tokenizerFile.readText())
                }
                withContext(Dispatchers.Default) {
                    val newRunner = OrtOnnxRunner(
                        encoderFile = java.io.File(dir, ENCODER_FILE),
                        decoderFile = java.io.File(dir, DECODER_FILE),
                    )
                    try {
                        newRunner.open()
                        // A cancel landing here must not leave an opened runner
                        // nobody owns.
                        currentCoroutineContext().ensureActive()
                        runner = newRunner
                    } catch (failure: Throwable) {
                        runCatching { newRunner.close() }
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                releaseRunner()
                tokenizer = null
                throw cancelled
            } catch (failure: Throwable) {
                releaseRunner()
                tokenizer = null
                throw EngineInitException(failure)
            }
        }
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        ensureReady()
        // Stock lines the dictionary owns outright (rapid commands,
        // interjections). These are Egyptian renderings: Egyptian mode only,
        // never leaked into an MSA answer.
        if (request.arabicMode == com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC) {
            dictionary.get().overrideFor(request.text)?.let { override ->
                return TranslationResult(translatedText = override, engineId = "$ENGINE_ID+dict")
            }
        }
        val localTokenizer = tokenizer ?: throw ModelNotInstalledException()
        val localRunner = runner ?: throw ModelNotInstalledException()
        return withContext(Dispatchers.Default) {
            withInference {
                val inputIds = localTokenizer.encode(request.text)
                // Captured once: the cancellation lambda is not a suspend
                // context, so it reads the captured context's Job state.
                val requestContext = currentCoroutineContext()
                val decoder = GreedyDecoder(
                    runner = localRunner,
                    decoderStartId = DECODER_START_ID,
                    eosId = EOS_ID,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    // Real cancellation: a timed-out or stopped request must
                    // abort the decode loop, not run all 128 tokens while the
                    // watchdog waits on a consumer that ignores cancel().
                    isCancelled = { !requestContext.isActive },
                )
                val outputIds = try {
                    decoder.generate(inputIds)
                } catch (cancelled: GreedyDecoder.DecodeCancelledException) {
                    throw CancellationException("decode cancelled", cancelled)
                }
                currentCoroutineContext().ensureActive()
                val msa = localTokenizer.decode(outputIds)
                if (msa.isBlank()) {
                    throw EmptyModelOutputException()
                }
                // Marian speaks MSA natively. Egyptian mode gets the
                // dictionary MSA->Egyptian pass; MSA mode keeps the model's
                // own output untouched (no Egyptian term can leak in, and
                // the brief forbids claiming a prompt-less model changed
                // register - the dictionary pass IS the Egyptian layer).
                val out = if (request.arabicMode == com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC) {
                    dictionary.get().toEgyptian(request.text, msa)
                } else {
                    msa
                }
                // Honest engine id, same convention as the ML Kit fallback:
                // when the dictionary pass rewrote the wording the tag says
                // so instead of claiming pure model output.
                val tag = if (out == msa) ENGINE_ID else "$ENGINE_ID+dict"
                TranslationResult(translatedText = out, engineId = tag)
            }
        }
    }

    private fun releaseRunner() {
        val old = runner
        runner = null
        if (old != null) closeWhenIdle(old)
    }

    /** Closes [target] now, or right after the running inference when one holds it. */
    private fun closeWhenIdle(target: OrtOnnxRunner) {
        if (inferenceMutex.tryLock()) {
            try {
                runCatching { target.close() }
            } finally {
                inferenceMutex.unlock()
            }
        } else {
            pendingClose.add(target)
        }
    }


    /** Runs one inference under the lock and finishes any close deferred during it. */
    private suspend fun <T> withInference(block: suspend () -> T): T =
        inferenceMutex.withLock {
            try {
                block()
            } finally {
                drainPendingClose()
            }
        }

    private fun drainPendingClose() {
        while (true) {
            val next = pendingClose.poll() ?: return
            if (next === runner) continue
            runCatching { next.close() }
        }
    }

    /**
     * Releases the model sessions; the next translate reloads lazily. Never
     * closes native sessions under a running decode: that close is deferred
     * until the inference finishes.
     */
    override fun close() {
        releaseRunner()
        tokenizer = null
    }

    /** Model produced an empty/blank decode: a visible failure, not a silent pass. */
    class EmptyModelOutputException : Exception("empty_model_output")

    /** Native model load failed; wrapped as an Exception so callers degrade. */
    class EngineInitException(cause: Throwable) : Exception("local_engine_init_failed", cause)

    companion object {
        const val ENGINE_ID = "local-ai-v1"
        private const val ENCODER_FILE = "encoder.onnx"
        private const val DECODER_FILE = "decoder.onnx"
        private const val TOKENIZER_FILE = "tokenizer.json"
        private const val DECODER_START_ID = 62801
        private const val EOS_ID = 0
        private const val MAX_OUTPUT_TOKENS = 128
    }
}

/**
 * Holds the active [EgyptianDictionary]: the bundled asset by default, replaced
 * by a newer downloaded version when one exists. Injected as a singleton so the
 * engine and settings always see the same dictionary.
 */
@Singleton
class EgyptianDictionaryHolder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) {
    @Volatile
    private var active: EgyptianDictionary = loadBundled(context)

    /** Test-only seam: a fixed dictionary in place of the bundled asset. */
    @Volatile
    internal var dictionaryOverride: EgyptianDictionary? = null

    fun get(): EgyptianDictionary = dictionaryOverride ?: active

    /** Swaps in a newer dictionary (e.g. after an update download). Ignores older versions. */
    fun update(jsonText: String) {
        val candidate = EgyptianDictionary.load(jsonText)
        if (candidate.version > (dictionaryOverride ?: active).version) active = candidate
    }

    /**
     * Verified local dictionary update: the payload must parse, its SHA-256
     * must equal [expectedSha256Hex], and its version must be newer than the
     * active one. Returns true only when the swap happened; any mismatch is
     * a silent no-op (the caller reports the failed check, the old
     * dictionary keeps serving).
     */
    fun updateVerified(jsonText: String, expectedSha256Hex: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(jsonText.toByteArray(Charsets.UTF_8))
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256Hex.trim().lowercase(), ignoreCase = false)) return false
        val candidate = runCatching { EgyptianDictionary.load(jsonText) }.getOrNull() ?: return false
        if (candidate.version <= (dictionaryOverride ?: active).version) return false
        active = candidate
        return true
    }

    private fun loadBundled(context: android.content.Context): EgyptianDictionary {
        val text = context.assets.open("local_ai/egyptian_dictionary.json")
            .bufferedReader().use { it.readText() }
        return EgyptianDictionary.load(text)
    }

}
