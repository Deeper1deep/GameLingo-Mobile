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
                    newRunner.open()
                    runner = newRunner
                }
            } catch (cancelled: CancellationException) {
                runner = null
                tokenizer = null
                throw cancelled
            } catch (failure: Throwable) {
                runner = null
                tokenizer = null
                throw EngineInitException(failure)
            }
        }
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        ensureReady()
        // Stock lines the dictionary owns outright (rapid commands, interjections).
        dictionary.get().overrideFor(request.text)?.let { override ->
            return TranslationResult(translatedText = override, engineId = "$ENGINE_ID+dict")
        }
        val localTokenizer = tokenizer ?: throw ModelNotInstalledException()
        val localRunner = runner ?: throw ModelNotInstalledException()
        return withContext(Dispatchers.Default) {
            inferenceMutex.withLock {
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
                val egyptian = dictionary.get().toEgyptian(request.text, msa)
                TranslationResult(translatedText = egyptian, engineId = ENGINE_ID)
            }
        }
    }

    /** Releases the model sessions; the next translate reloads lazily. */
    override fun close() {
        runner?.close()
        runner = null
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

    private fun loadBundled(context: android.content.Context): EgyptianDictionary {
        val text = context.assets.open("local_ai/egyptian_dictionary.json")
            .bufferedReader().use { it.readText() }
        return EgyptianDictionary.load(text)
    }

}
