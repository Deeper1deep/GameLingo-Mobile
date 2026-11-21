package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.context.ContextMemory
import com.gamelingo.mobile.core.session.RequestState
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.timing.PipelineTiming
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Watches the session bus for accepted OCR lines and hands them to the
 * [TranslationWorker], whose bounded queue, single consumer and watchdog
 * keep translations flowing even when individual requests fail or stall.
 * A newer subtitle line never cancels an in-flight translation anymore:
 * lines are processed in arrival order, and the queue's drop-oldest policy
 * keeps a burst of stale lines from starving fresh ones.
 *
 * Honours the user's settings on every line: AI mode and API key (inside
 * the hybrid engine), request timeout, cache enablement and context line
 * count. The cache key covers the full translation context (mode, planned
 * engine, prompt version, dialogue context, language pair), so a mode
 * switch or a prompt upgrade never serves a stale answer.
 *
 * When the effective engine is not the one the user picked (ONLINE without
 * setup, or an AUTO fallback after an online failure), a [SessionNotice] is
 * published so the UI can say so instead of hiding the downgrade.
 */
@Singleton
class TranslationCoordinator @Inject constructor(
    private val hybridEngine: HybridTranslationEngine,
    private val settingsRepository: SettingsRepository,
    private val historyDao: TranslationHistoryDao,
    private val cache: TranslationCache,
    private val modelManager: com.gamelingo.mobile.translate.local.LocalModelManager,
    private val dictionaryHolder: com.gamelingo.mobile.translate.local.EgyptianDictionaryHolder,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val contextMemory = ContextMemory(maxLines = MAX_CONTEXT_LINES)

    /** Visible for tests: the worker driving every translation request. */
    var worker: TranslationWorker? = null
        private set

    /** Idempotent: safe to call from every service start and app launch. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val translationWorker = TranslationWorker(processor = ::processRequest)
        worker = translationWorker
        translationWorker.start()
        scope.launch {
            // The lossless worker stream, NOT latestLine: a StateFlow
            // conflates, so collecting latestLine here silently dropped
            // subtitle lines under fast dialogue. The UI keeps latestLine;
            // the pipeline gets every line.
            TranslationSessionBus.ocrLines.collect { line ->
                translationWorker.enqueue(line.text, line.capturedAtMillis)
            }
        }
        // The worker follows the session: a stopped session stops it
        // (queued lines belong to that session and must not leak into the
        // next one), and a fresh start re-arms it. Both calls are
        // idempotent, so repeated starts never spawn a second consumer.
        scope.launch {
            TranslationSessionBus.state.collect { state ->
                when (state) {
                    SessionState.RUNNING -> translationWorker.start()
                    SessionState.STOPPED -> {
                        translationWorker.stop()
                        contextMemory.clear()
                    }
                    SessionState.ERROR -> Unit
                }
            }
        }
    }

    /**
     * Translates one queued line: cache, engine call with the user's
     * timeout, publication, history. Failures are surfaced to the bus and
     * then rethrown so the worker can classify them into a [RequestState];
     * a cancellation (session stop or worker recovery) always propagates.
     */
    private suspend fun processRequest(request: QueuedRequest): ProcessorOutcome {
        // Session generation guard: if the session stops or restarts while
        // this request is in flight, its result belongs to the old session
        // and must not be published into the new one.
        val generation = TranslationSessionBus.currentGeneration()
        val settings = settingsRepository.settings.first()
        val arabicMode = settings.arabicTranslationMode
        val contextLines = contextMemory.snapshot().takeLast(settings.contextLines)
        val plannedEngine = hybridEngine.plannedEngineId()
        val cacheContext = CacheContext(
            aiMode = settings.aiMode,
            plannedEngineId = plannedEngine,
            arabicMode = arabicMode,
            promptVersion = ArabicStylePrompt.promptVersionFor(arabicMode),
            contextSignature = CacheContext.signatureOf(contextLines),
            // A model or dictionary update changes the output for the same
            // input; key on both so an update never serves pre-update entries.
            modelVersion = installedVersionFor(plannedEngine),
            dictionaryVersion = dictionaryHolder.get().version,
        )

        if (settings.cacheEnabled) {
            cache.get(request.text, cacheContext)?.let { hit ->
                if (!isPublishableNow(generation, arabicMode)) return ProcessorOutcome(hit.engineId, false)
                publish(
                    original = request.text,
                    result = TranslationResult(
                        translatedText = hit.translatedText,
                        engineId = hit.engineId,
                        fromCache = true,
                    ),
                    capturedAtMillis = request.capturedAtMillis,
                )
                contextMemory.add(request.text)
                return ProcessorOutcome(
                    engineId = hit.engineId,
                    usedLocalFallback = isLocalFallback(settings.aiMode, hit.engineId),
                )
            }
        }

        val engineStartNanos = System.nanoTime()
        try {
            val result = withTimeout(settings.requestTimeoutSec * MILLIS_PER_SECOND) {
                hybridEngine.translate(
                    TranslationRequest(
                        text = request.text,
                        contextLines = contextLines,
                        // Feed the terminology layer: prompt-capable engines
                        // (Gemini) render the established gaming terms into
                        // the prompt instead of guessing them. Mode-aware:
                        // the Egyptian layer never reaches an MSA prompt and
                        // vice versa.
                        glossary = dictionaryHolder.get().glossaryTerms(request.text, arabicMode),
                        arabicMode = arabicMode,
                    ),
                )
            }
            PipelineTiming.mark(
                "engine",
                (System.nanoTime() - engineStartNanos) / NANOS_PER_MS,
                result.engineId,
            )
            PipelineTiming.endToEnd("translated", request.capturedAtMillis)
            if (settings.cacheEnabled) {
                cache.put(
                    request.text,
                    cacheContext,
                    CachedTranslation(
                        translatedText = result.translatedText,
                        engineId = result.engineId,
                    ),
                )
            }
            // Mode-change guard: if the user switched Arabic style while
            // this line was in flight, its old-mode result must not appear
            // over the new mode. The cache entry is still stored (keyed by
            // mode, so it only ever serves the mode it belongs to).
            if (isPublishableNow(generation, arabicMode)) {
                publish(request.text, result, request.capturedAtMillis)
                contextMemory.add(request.text)
                publishEngineNotice(settings.aiMode, result.engineId)
            }
            return ProcessorOutcome(
                engineId = result.engineId,
                usedLocalFallback = isLocalFallback(settings.aiMode, result.engineId),
            )
        } catch (timeout: TimeoutCancellationException) {
            // The provider hit the user's timeout: a visible failure. The
            // worker classifies the state; checked before CancellationException.
            if (!isStale(generation)) TranslationSessionBus.onTranslationFailed(request.text)
            throw timeout
        } catch (cancelled: CancellationException) {
            // Session stop or worker recovery: never misreport as a failure.
            throw cancelled
        } catch (error: Exception) {
            // The user sees the line failed; the worker sees the typed error
            // and moves to the next queued line.
            when (error) {
                is OnlineNotConfiguredException ->
                    TranslationSessionBus.onNotice(SessionNotice.ONLINE_NOT_CONFIGURED)
                else -> Unit
            }
            if (!isStale(generation)) TranslationSessionBus.onTranslationFailed(request.text)
            throw error
        }
    }

    /**
     * True when the session that queued this request is no longer the
     * running one: its results and failures must not reach the overlay or
     * the failure banner of the new session.
     */
    private fun isStale(generation: Long): Boolean =
        TranslationSessionBus.state.value != SessionState.RUNNING ||
            TranslationSessionBus.currentGeneration() != generation

    /** Live form of the pure gate [isPublishable]: session and mode still current. */
    private suspend fun isPublishableNow(
        generation: Long,
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
    ): Boolean = isPublishable(
        sessionRunning = TranslationSessionBus.state.value == SessionState.RUNNING,
        capturedGeneration = generation,
        currentGeneration = TranslationSessionBus.currentGeneration(),
        requestMode = mode,
        // A fresh settings read is cheap (DataStore caches its state) and
        // needs no extra observer.
        currentMode = settingsRepository.settings.first().arabicTranslationMode,
    )

    /** Installed version of the model backing [plannedEngine], or 0 for engines without one. */
    private fun installedVersionFor(plannedEngine: String): Int = when (plannedEngine) {
        com.gamelingo.mobile.translate.local.LocalAiTranslationEngine.ENGINE_ID ->
            modelManager.installedVersion(com.gamelingo.mobile.translate.local.LocalModelManager.MODEL_MARIAN) ?: 0
        com.gamelingo.mobile.translate.local.TranslateGemmaEngine.ENGINE_ID ->
            modelManager.installedVersion(com.gamelingo.mobile.translate.local.LocalModelManager.MODEL_TRANSLATEGEMMA) ?: 0
        else -> 0
    }

    private fun isLocalFallback(mode: AiMode, actualEngineId: String): Boolean =
        mode != AiMode.LOCAL && actualEngineId == MlKitTranslationEngine.ENGINE_ID

    /** Tell the user when the engine that answered is not the one they picked. */
    private fun publishEngineNotice(mode: AiMode, actualEngineId: String) {
        if (isLocalFallback(mode, actualEngineId)) {
            TranslationSessionBus.onNotice(SessionNotice.FELL_BACK_TO_LOCAL)
        }
    }

    private suspend fun publish(
        original: String,
        result: TranslationResult,
        capturedAtMillis: Long,
    ) {
        TranslationSessionBus.onTranslated(
            original = original,
            translated = result.translatedText,
            engineId = result.engineId,
            fromCache = result.fromCache,
            capturedAtMillis = capturedAtMillis,
        )
        // History is best-effort: a Room failure must never crash the app or
        // swallow a translation the user is waiting for.
        runCatching {
            historyDao.insert(
                TranslationHistoryEntity(
                    sourceText = original,
                    translatedText = result.translatedText,
                    engine = result.engineId,
                    wasCached = result.fromCache,
                    createdAt = capturedAtMillis,
                ),
            )
        }
    }

    internal companion object {
        const val MAX_CONTEXT_LINES = 20
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_MS = 1_000_000L

        /**
         * Pure publish gate, unit-tested: an in-flight result appears only
         * when the session that queued it is still current AND the Arabic
         * style it was built for is still the user's choice. A mode switch
         * mid-flight drops the old-mode result here instead of showing it
         * over the new mode.
         */
        internal fun isPublishable(
            sessionRunning: Boolean,
            capturedGeneration: Long,
            currentGeneration: Long,
            requestMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
            currentMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
        ): Boolean =
            sessionRunning &&
                capturedGeneration == currentGeneration &&
                requestMode == currentMode
    }
}
