package com.gamelingo.mobile.translate.local

import android.app.ActivityManager
import android.content.Context
import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * HD local engine: TranslateGemma 4B (int4, LiteRT-LM) — the promptable LLM
 * translator that produces realistic Egyptian colloquial output, which the Marian
 * NMT engine structurally cannot. Downloaded separately (~1.9 GB), verified by
 * SHA-256, and run fully on-device: zero network during translation.
 *
 * Same guarantees as the Marian engine: single warm engine instance behind one
 * mutex, all work off the main thread, coroutine cancellation aborts the
 * generation, and a missing/unverified model fails visibly instead of faking it.
 *
 * A fresh Conversation is created per line: keeping history would let earlier
 * subtitles steer later ones and would grow the context unboundedly. Session
 * creation cost is measured on-device and reported honestly.
 */
@Singleton
class TranslateGemmaEngine @Inject constructor(
    private val modelManager: LocalModelManager,
    private val dictionary: EgyptianDictionaryHolder,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : LocalAiCandidate {

    class ModelNotInstalledException : Exception("translategemma_not_installed")

    /** Not enough free RAM to load a ~2GB model safely; refuse instead of dying. */
    class InsufficientMemoryException : Exception("translategemma_low_memory")

    override val engineId: String = ENGINE_ID

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    override fun isAvailable(): Boolean = modelManager.isInstalled(LocalModelManager.MODEL_TRANSLATEGEMMA)

    override suspend fun ensureReady() {
        if (engine != null) return
        loadMutex.withLock {
            if (engine != null) return
            val modelFile = modelManager.modelFile(LocalModelManager.MODEL_TRANSLATEGEMMA, MODEL_FILE)
                ?: throw ModelNotInstalledException()
            // Pre-flight memory gate: loading a ~2GB model when the device cannot
            // spare it is a blind attempt at a guaranteed native OOM kill (the
            // kernel SIGKILLs the app; no Kotlin catch can stop that). Refuse
            // honestly instead and let the chain degrade to Marian.
            if (!hasEnoughMemory(availableMemoryBytes(), modelFile.length())) {
                throw InsufficientMemoryException()
            }
            withContext(Dispatchers.Default) {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    // CPU: Mali-G68 GPU support is unverified on the A35; measure
                    // CPU first, try GPU on-device later and report honestly.
                    backend = Backend.CPU(),
                )
                // Throwable containment: LiteRT-LM init failures surface as
                // Errors (linkage, native checks), which the chain turns into a
                // visible degrade instead of a process crash.
                val newEngine = try {
                    val created = Engine(config)
                    created.initialize()
                    created
                } catch (cancelled: Throwable) {
                    engine = null
                    if (cancelled is kotlinx.coroutines.CancellationException) throw cancelled
                    throw EngineInitException(cancelled)
                }
                engine = newEngine
            }
        }
    }

    private fun availableMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** Engine load failed at native level; surfaced as an Exception so callers degrade. */
    class EngineInitException(cause: Throwable) : Exception("translategemma_init_failed", cause)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        ensureReady()
        dictionary.get().overrideFor(request.text)?.let { override ->
            return TranslationResult(translatedText = override, engineId = "$ENGINE_ID+dict")
        }
        val localEngine = engine ?: throw ModelNotInstalledException()
        return withContext(Dispatchers.Default) {
            inferenceMutex.withLock {
                val conversation = localEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2),
                    ),
                )
                try {
                    val prompt = buildPrompt(request)
                    val sb = conversation.sendMessageAsync(prompt)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            throw GenerationFailedException(e.message ?: "generation failed")
                        }
                        .fold(StringBuilder()) { acc, message ->
                            message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .forEach { acc.append(it.text) }
                            acc
                        }
                        .toString()
                        .trim()
                    if (sb.isEmpty()) throw GenerationFailedException("empty output")
                    // Light dictionary pass: glossary consistency + known-term fixes.
                    // The LLM owns style; the dictionary only corrects what it knows.
                    val polished = dictionary.get().toEgyptian(request.text, sb)
                    TranslationResult(translatedText = polished, engineId = ENGINE_ID)
                } finally {
                    conversation.close()
                }
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    class GenerationFailedException(message: String) : Exception(message)

    companion object {
        const val ENGINE_ID = "translategemma-4b-v1"
        private const val MODEL_FILE = "model.litertlm"

        /**
         * Free RAM the device must keep beyond the model file itself: runtime
         * activations + the game + the OS. 1.2 GB is deliberately conservative.
         */
        internal const val MIN_FREE_HEADROOM_BYTES = 1_200_000_000L

        /** Pure gate, unit-tested: enough free RAM to load modelBytes safely? */
        internal fun hasEnoughMemory(availMemBytes: Long, modelBytes: Long): Boolean =
            availMemBytes >= modelBytes + MIN_FREE_HEADROOM_BYTES

        /**
         * Dialect mandate: natural spoken Egyptian, preserve profanity intensity,
         * never soften, never invent, keep gaming terms per glossary, no MSA unless
         * the source is formal, no explanations - the translated line only.
         */
        private const val SYSTEM_INSTRUCTION =
            "You are a professional English to Egyptian Arabic translator for video game subtitles. " +
                "Translate into natural spoken Egyptian colloquial Arabic (عامية مصرية واقعية), exactly how a " +
                "real Egyptian gamer would say it, never formal MSA. Rules: keep the exact profanity intensity " +
                "of the source (never soften or delete profanity, never add profanity that is not there); keep " +
                "sarcasm and tone; keep character names transliterated in Arabic letters; keep gaming terms " +
                "natural; translate idioms by meaning, never word-for-word; never omit any part of the line. " +
                "Output ONLY the translated line, with no explanations, no notes, no quotes."

        private fun buildPrompt(request: TranslationRequest): String {
            val sb = StringBuilder()
            if (request.glossary.isNotEmpty()) {
                sb.append("Glossary (use these exact renderings): ")
                request.glossary.entries.take(24).joinTo(sb, "; ") { "${it.key} = ${it.value}" }
                sb.append('\n')
            }
            if (request.characterNames.isNotEmpty()) {
                sb.append("Characters: ")
                request.characterNames.take(12).joinTo(sb, ", ")
                sb.append('\n')
            }
            if (request.contextLines.isNotEmpty()) {
                sb.append("Previous lines (context only, do not translate):\n")
                request.contextLines.takeLast(3).forEach { sb.append("- ").append(it).append('\n') }
            }
            sb.append("Translate this line into Egyptian Arabic:\n").append(request.text)
            return sb.toString()
        }
    }
}
