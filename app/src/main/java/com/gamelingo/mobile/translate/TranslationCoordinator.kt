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
import kotlinx.coroutines.cancel
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
    private val understandingHolder: com.gamelingo.mobile.core.understanding.EnglishUnderstandingHolder,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    /** Test support: end this coordinator's collectors so it cannot keep consuming the shared bus. */
    internal fun shutdownForTest() {
        worker?.stop()
        scope.cancel()
    }
    private fun sourceOf(subtitleId: Long): com.gamelingo.mobile.core.context.ContextMemory.Source = when {
        subtitleId == 0L -> com.gamelingo.mobile.core.context.ContextMemory.Source.UNKNOWN
        com.gamelingo.mobile.core.session.TranslationSessionBus.isAudioId(subtitleId) -> com.gamelingo.mobile.core.context.ContextMemory.Source.AUDIO
        else -> com.gamelingo.mobile.core.context.ContextMemory.Source.OCR
    }

    private val contextMemory = ContextMemory(maxLines = MAX_CONTEXT_LINES)
    @Volatile private var lastContextMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode? = null
    private val historyRecorder = HistoryRecorder.over(historyDao)

    /** Visible for tests: the worker driving every translation request. */
    var worker: TranslationWorker? = null
        private set

    /** Idempotent: safe to call from every service start and app launch. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val translationWorker = TranslationWorker(
            // Every captured line is translated even when it has left the screen by now: a late
            // translation goes to the history instead of being skipped.
            processor = { processRequest(it, includeOffscreen = true) },
            batchProcessor = ::processBatch,
            recentOnlineRequests = { OnlineRequestGate.shared.startsInLast(RECENT_WINDOW_MS) },
            // A bounded backlog: while the provider's wait is active lines queue instead of being skipped.
            queueCapacity = BACKLOG_CAPACITY,
            maxQueueAgeMs = BACKLOG_MAX_AGE_MS,
            cooldownRemainingMs = { OnlineRequestGate.shared.remainingMs() },
            isRelevant = { r ->
                TranslationSessionBus.state.value == SessionState.RUNNING &&
                    (r.generation == TranslationSessionBus.NO_ID ||
                        r.generation == TranslationSessionBus.currentGeneration()) &&
                    TranslationSessionBus.isSubtitleActive(r.subtitleId)
            },
        )
        worker = translationWorker
        translationWorker.start()
        scope.launch {
            // The lossless worker stream, NOT latestLine: a StateFlow
            // conflates, so collecting latestLine here silently dropped
            // subtitle lines under fast dialogue. The UI keeps latestLine;
            // the pipeline gets every line.
            TranslationSessionBus.ocrLines.collect { line ->
                // Lines buffered by a previous session (stop then a quick
                // restart) belong to that session: never translate them into
                // the new one.
                val startedAt = TranslationSessionBus.startedAtMillis.value
                if (TranslationSessionBus.state.value != SessionState.RUNNING ||
                    (startedAt != null && line.capturedAtMillis < startedAt)
                ) {
                    return@collect
                }
                translationWorker.enqueue(
                    text = line.text,
                    capturedAtMillis = line.capturedAtMillis,
                    subtitleId = line.subtitleId,
                    generation = line.generation,
                )
            }
        }
        scope.launch {
            var seen = TranslationSessionBus.contextResetToken.value
            TranslationSessionBus.contextResetToken.collect { token ->
                if (token != seen) { seen = token; contextMemory.clear(); com.gamelingo.mobile.core.context.SuggestionPool.clear() }
            }
        }
        // The worker follows the session: a stopped session stops it
        // (queued lines belong to that session and must not leak into the
        // next one), and a fresh start re-arms it. Both calls are
        // idempotent, so repeated starts never spawn a second consumer.
        scope.launch {
            TranslationSessionBus.state.collect { state ->
                when (state) {
                    SessionState.RUNNING -> {
                        // Starting a session is the deliberate recovery from an indefinite block.
                        OnlineRequestGate.shared.clearIndefiniteBlock()
                        translationWorker.start()
                    }
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
    private suspend fun processRequest(queued: QueuedRequest, includeOffscreen: Boolean = false): ProcessorOutcome {
        // OCR clean-up is its own step, before translation and before the
        // cache key: only safe fixes (whitespace, doubled capture, "|" for
        // "I"); anything uncertain stays exactly as read.
        val cleaned = com.gamelingo.mobile.core.text.OcrPreprocessor.clean(queued.text).text
        val request = if (cleaned.isNotBlank() && cleaned != queued.text) queued.copy(text = cleaned) else queued
        // Session generation guard: if the session stops or restarts while
        // this request is in flight, its result belongs to the old session
        // and must not be published into the new one.
        val generation = if (request.generation != TranslationSessionBus.NO_ID) {
            request.generation
        } else {
            TranslationSessionBus.currentGeneration()
        }
        // The subtitle may have left the screen while the request waited in
        // the queue: translating a gone line only burns tokens and battery.
        if (isStale(generation) || (!includeOffscreen && !TranslationSessionBus.isSubtitleActive(request.subtitleId))) {
            return ProcessorOutcome("skipped-stale", false)
        }
        val settings = settingsRepository.settings.first()
        val arabicMode = settings.arabicTranslationMode
        // A different Arabic style is a different conversation setup: the old
        // lines were translated under the other style and must not steer this one.
        if (lastContextMode != null && lastContextMode != arabicMode) contextMemory.clear()
        lastContextMode = arabicMode
        val contextLines = contextMemory.select(request.text, settings.contextLines, MAX_CONTEXT_CHARS, sourceOf(request.subtitleId))
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
            understandingVersion = understandingHolder.get().version,
            // The user's pinned terms change the prompt, so they are part of
            // the cache key: adding or editing a term never serves old lines.
            glossarySignature = CacheContext.glossarySignatureOf(
                settings.userGlossaryTerms.map { it.en to it.ar },
            ),
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
                    subtitleId = request.subtitleId,
                    generation = generation,
                )
                contextMemory.add(request.text, request.capturedAtMillis, sourceOf(request.subtitleId), hit.translatedText, request.subtitleId, arabicMode.name)
                com.gamelingo.mobile.core.context.SuggestionPool.record(request.text)
                return ProcessorOutcome(
                    engineId = hit.engineId,
                    usedLocalFallback = isLocalFallback(settings.aiMode, hit.engineId),
                )
            }
        }

        val engineStartNanos = System.nanoTime()
        try {
            val baseRequest = TranslationRequest(
                text = request.text,
                contextLines = contextLines,
                // Feed the terminology layer: prompt-capable engines
                // (Gemini) render the established gaming terms into
                // the prompt instead of guessing them. Mode-aware:
                // the Egyptian layer never reaches an MSA prompt and
                // vice versa.
                glossary = mergedGlossary(request.text, arabicMode, settings),
                arabicMode = arabicMode,
                // Non-literal expressions as meaning hints for
                // prompt-capable engines; empty for plain lines.
                understandingHints = understandingHolder.get()
                    .promptHints(request.text, arabicMode),
            )
            // At most one retry, with the dialogue context dropped, for an
            // output that looks corrupted (loop, leaked instructions, empty
            // or runaway text). A second bad answer is rejected: never
            // cached, published or saved as a translation.
            val attempt = com.gamelingo.mobile.core.quality.BoundedRetry.run(
                first = { translateOnce(baseRequest, settings.requestTimeoutSec, arabicMode, request.text) },
                retry = {
                    translateOnce(
                        baseRequest.copy(contextLines = emptyList()),
                        settings.requestTimeoutSec, arabicMode, request.text,
                    )
                },
                isCorrupt = { com.gamelingo.mobile.core.quality.TranslationQualityGate.isCorrupt(it.second) },
            )
            // A rejected output may still be rescued by the line's one known
            // reference rendering (local, deterministic); otherwise it fails.
            val rescue = if (attempt.accepted) null else {
                understandingHolder.get().exactLineRendering(request.text, arabicMode)
                    ?: run {
                        com.gamelingo.mobile.core.diagnostics.PipelineTrace.record(
                            com.gamelingo.mobile.core.diagnostics.PipelineTrace.Event(
                                System.currentTimeMillis(), request.subtitleId, "rejected",
                                attempt.value.first.engineId, request.text.length,
                                attempt.value.first.translatedText.length,
                                attempt.value.second.issues.map { it.name }, attempt.retried,
                                false, (System.nanoTime() - engineStartNanos) / NANOS_PER_MS,
                            ),
                        )
                        throw RejectedOutputException()
                    }
            }
            val result = if (rescue != null) {
                attempt.value.first.copy(translatedText = rescue, engineId = "${attempt.value.first.engineId}+idiom")
            } else {
                attempt.value.first
            }
            val inspection = if (rescue != null) {
                com.gamelingo.mobile.core.quality.TranslationQualityGate.Inspection(rescue, emptyList(), false)
            } else {
                attempt.value.second
            }
            // A flagged output for a line that is, as a whole, one known
            // expression ("This is us.") is replaced by that expression's
            // reference rendering: local, deterministic, no second engine call.
            val knownRendering = if (inspection.issues.any { it in REPLACEABLE_ISSUES }) {
                understandingHolder.get().exactLineRendering(request.text, arabicMode)
            } else {
                null
            }
            val finalResult = when {
                knownRendering != null -> result.copy(
                    translatedText = knownRendering,
                    engineId = "${result.engineId}+idiom",
                )
                inspection.corrected -> result.copy(
                    translatedText = inspection.text,
                    engineId = "${result.engineId}+qc",
                )
                else -> result
            }
            PipelineTiming.mark(
                "engine",
                (System.nanoTime() - engineStartNanos) / NANOS_PER_MS,
                finalResult.engineId,
            )
            PipelineTiming.endToEnd("translated", request.capturedAtMillis)
            com.gamelingo.mobile.core.diagnostics.PipelineTrace.record(
                com.gamelingo.mobile.core.diagnostics.PipelineTrace.Event(
                    System.currentTimeMillis(), request.subtitleId,
                    if (rescue != null) "rescued" else "accepted",
                    finalResult.engineId, request.text.length, finalResult.translatedText.length,
                    inspection.issues.map { it.name }, attempt.retried, false,
                    (System.nanoTime() - engineStartNanos) / NANOS_PER_MS,
                ),
            )
            if (settings.cacheEnabled) {
                cache.put(
                    request.text,
                    cacheContext,
                    CachedTranslation(
                        translatedText = finalResult.translatedText,
                        engineId = finalResult.engineId,
                    ),
                )
            }
            // Mode-change guard: if the user switched Arabic style while
            // this line was in flight, its old-mode result must not appear
            // over the new mode. The cache entry is still stored (keyed by
            // mode, so it only ever serves the mode it belongs to).
            if (isPublishableNow(generation, arabicMode)) {
                publish(
                    request.text, finalResult, request.capturedAtMillis,
                    request.subtitleId, generation,
                )
                contextMemory.add(request.text, request.capturedAtMillis, sourceOf(request.subtitleId), finalResult.translatedText, request.subtitleId, arabicMode.name)
                com.gamelingo.mobile.core.context.SuggestionPool.record(request.text)
                publishEngineNotice(settings.aiMode, finalResult.engineId)
            }
            return ProcessorOutcome(
                engineId = finalResult.engineId,
                usedLocalFallback = isLocalFallback(settings.aiMode, finalResult.engineId),
            )
        } catch (timeout: TimeoutCancellationException) {
            // The provider hit the user's timeout: a visible failure. The
            // worker classifies the state; checked before CancellationException.
            if (!isStale(generation)) TranslationSessionBus.onTranslationFailed(
                request.text, request.subtitleId, generation,
            )
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
            if (!isStale(generation)) TranslationSessionBus.onTranslationFailed(
                request.text, request.subtitleId, generation,
            )
            throw error
        }
    }

    private class Prepared(
        val queued: QueuedRequest,
        val request: QueuedRequest,
        val generation: Long,
        val contextLines: List<String>,
        val cacheContext: CacheContext,
    )

    /**
     * Translates several waiting lines with ONE provider request.
     *
     * Rules that keep sentences safe:
     * - cached lines are answered from the cache first, in order;
     * - the answer must be exactly one non-blank translation per line, else none of it is used and
     *   every line goes through the normal one-line path;
     * - each translation still passes the quality gate; a flagged one is redone on its own;
     * - a line the provider's rate limit deferred is handed back (never dropped here);
     * - any other failure is shown as a visible failure for on-screen lines and counted as lost.
     * A late translation is saved to the history even when the line has left the screen.
     */
    private suspend fun processBatch(batch: List<QueuedRequest>): BatchResult {
        val settings = settingsRepository.settings.first()
        val arabicMode = settings.arabicTranslationMode
        if (lastContextMode != null && lastContextMode != arabicMode) contextMemory.clear()
        lastContextMode = arabicMode
        val plannedEngine = hybridEngine.plannedEngineId()
        val pending = ArrayList<Prepared>()
        var lastOutcome: ProcessorOutcome? = null
        for (queued in batch) {
            val cleaned = com.gamelingo.mobile.core.text.OcrPreprocessor.clean(queued.text).text
            val request = if (cleaned.isNotBlank() && cleaned != queued.text) queued.copy(text = cleaned) else queued
            val generation = if (request.generation != TranslationSessionBus.NO_ID) {
                request.generation
            } else {
                TranslationSessionBus.currentGeneration()
            }
            if (isStale(generation)) continue
            val contextLines = contextMemory.select(request.text, settings.contextLines, MAX_CONTEXT_CHARS, sourceOf(request.subtitleId))
            val cacheContext = cacheContextFor(settings, arabicMode, plannedEngine, contextLines)
            val hit = if (settings.cacheEnabled) cache.get(request.text, cacheContext) else null
            if (hit != null) {
                if (isPublishableNow(generation, arabicMode)) {
                    publish(
                        original = request.text,
                        result = TranslationResult(hit.translatedText, hit.engineId, fromCache = true),
                        capturedAtMillis = request.capturedAtMillis,
                        subtitleId = request.subtitleId,
                        generation = generation,
                    )
                    contextMemory.add(request.text, request.capturedAtMillis, sourceOf(request.subtitleId), hit.translatedText, request.subtitleId, arabicMode.name)
                }
                lastOutcome = ProcessorOutcome(hit.engineId, isLocalFallback(settings.aiMode, hit.engineId))
            } else {
                pending.add(Prepared(queued, request, generation, contextLines, cacheContext))
            }
        }
        if (pending.isEmpty()) return BatchResult(lastOutcome ?: ProcessorOutcome("skipped-stale", false))

        val retry = ArrayList<QueuedRequest>()
        var lost = 0
        var firstError: Exception? = null

        /** One line through the normal path; classifies its failure for the batch result. */
        suspend fun single(item: Prepared) {
            try {
                lastOutcome = processRequest(item.queued, includeOffscreen = true)
            } catch (timeout: TimeoutCancellationException) {
                lost++
                if (firstError == null) firstError = timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is QuotaExceededException) retry.add(item.queued) else lost++
                if (firstError == null || error is QuotaExceededException) firstError = error
            }
        }

        if (pending.size == 1) {
            single(pending.first())
            return BatchResult(lastOutcome, retry, lost, firstError)
        }

        val requests = pending.map { item ->
            TranslationRequest(
                text = item.request.text,
                contextLines = item.contextLines,
                glossary = mergedGlossary(item.request.text, arabicMode, settings),
                arabicMode = arabicMode,
                understandingHints = understandingHolder.get().promptHints(item.request.text, arabicMode),
            )
        }
        val answers: List<String>? = try {
            withTimeout(settings.requestTimeoutSec * BATCH_TIMEOUT_FACTOR_MS) { hybridEngine.translateBatch(requests) }
        } catch (timeout: TimeoutCancellationException) {
            pending.forEach { failVisibly(it) }
            return BatchResult(lastOutcome, emptyList(), pending.size, timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (misaligned: BatchMisalignedException) {
            null
        } catch (refused: ProviderRefusedException) {
            null
        } catch (error: Exception) {
            // Quota: the lines wait for the provider's time and are sent again. Anything else: visible failure.
            pending.forEach { failVisibly(it) }
            if (error is OnlineNotConfiguredException) {
                TranslationSessionBus.onNotice(SessionNotice.ONLINE_NOT_CONFIGURED)
            }
            return if (error is QuotaExceededException) {
                BatchResult(lastOutcome, pending.map { it.queued }, 0, error)
            } else {
                BatchResult(lastOutcome, emptyList(), pending.size, error)
            }
        }
        if (answers == null) {
            // No batch path (local mode, active cooldown) or a misaligned answer: one line at a time.
            pending.forEach { single(it) }
            return BatchResult(lastOutcome, retry, lost, firstError)
        }
        for ((index, item) in pending.withIndex()) {
            val translated = answers[index]
            val inspection = com.gamelingo.mobile.core.quality.TranslationQualityGate
                .inspect(item.request.text, translated, arabicMode)
            if (inspection.issues.isNotEmpty() || inspection.corrected) {
                // Anything the gate doubts is redone on its own, with the full single-line checks.
                single(item)
                continue
            }
            val result = TranslationResult(translated, GeminiTranslationEngine.ENGINE_ID)
            if (settings.cacheEnabled) {
                cache.put(item.request.text, item.cacheContext, CachedTranslation(result.translatedText, result.engineId))
            }
            if (isPublishableNow(item.generation, arabicMode)) {
                publish(item.request.text, result, item.request.capturedAtMillis, item.request.subtitleId, item.generation)
                contextMemory.add(item.request.text, item.request.capturedAtMillis, sourceOf(item.request.subtitleId), result.translatedText, item.request.subtitleId, arabicMode.name)
                com.gamelingo.mobile.core.context.SuggestionPool.record(item.request.text)
            }
            lastOutcome = ProcessorOutcome(result.engineId, false)
        }
        return BatchResult(lastOutcome, retry, lost, firstError)
    }

    private fun failVisibly(item: Prepared) {
        if (!isStale(item.generation)) {
            TranslationSessionBus.onTranslationFailed(item.request.text, item.request.subtitleId, item.generation)
        }
    }

    private fun cacheContextFor(
        settings: com.gamelingo.mobile.data.settings.AppSettings,
        arabicMode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
        plannedEngine: String,
        contextLines: List<String>,
    ) = CacheContext(
        aiMode = settings.aiMode,
        plannedEngineId = plannedEngine,
        arabicMode = arabicMode,
        promptVersion = ArabicStylePrompt.promptVersionFor(arabicMode),
        contextSignature = CacheContext.signatureOf(contextLines),
        modelVersion = installedVersionFor(plannedEngine),
        dictionaryVersion = dictionaryHolder.get().version,
        understandingVersion = understandingHolder.get().version,
        glossarySignature = CacheContext.glossarySignatureOf(
            settings.userGlossaryTerms.map { it.en to it.ar },
        ),
    )

    /** One engine call under the user's timeout, plus the quality inspection of its output. */
    private suspend fun translateOnce(
        request: TranslationRequest,
        timeoutSec: Int,
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
        sourceText: String,
    ): Pair<TranslationResult, com.gamelingo.mobile.core.quality.TranslationQualityGate.Inspection> {
        val result = withTimeout(timeoutSec * MILLIS_PER_SECOND) { hybridEngine.translate(request) }
        val inspection = com.gamelingo.mobile.core.quality.TranslationQualityGate
            .inspect(sourceText, result.translatedText, mode)
        return result to inspection
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
        mode != AiMode.LOCAL && baseEngineId(actualEngineId) == MlKitTranslationEngine.ENGINE_ID

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
        subtitleId: Long = TranslationSessionBus.NO_ID,
        generation: Long = TranslationSessionBus.NO_ID,
    ) {
        TranslationSessionBus.onTranslated(
            original = original,
            translated = result.translatedText,
            engineId = result.engineId,
            fromCache = result.fromCache,
            capturedAtMillis = capturedAtMillis,
            subtitleId = subtitleId,
            generation = generation,
        )
        // History is best-effort: a Room failure must never crash the app or
        // swallow a translation the user is waiting for.
        runCatching {
            historyRecorder.record(
                subtitleId,
                TranslationSessionBus.NO_ID,
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

    /**
     * Bundled dictionary terms found in this line, overlaid with the user's
     * own pinned terms: a user term WINS over the bundled rendering of the
     * same source term, because a name the user pinned is a promise.
     */
    private fun mergedGlossary(
        text: String,
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
        settings: com.gamelingo.mobile.data.settings.AppSettings,
    ): Map<String, String> {
        val bundled = dictionaryHolder.get().glossaryTerms(text, mode)
        if (settings.userGlossaryTerms.isEmpty()) return bundled
        val dictionary = dictionaryHolder.get()
        val normalized = com.gamelingo.mobile.translate.local.EgyptianDictionary
            .normalizeEn(text)
        val merged = LinkedHashMap(bundled)
        for (term in settings.userGlossaryTerms) {
            val key = term.en.trim()
            if (key.isEmpty()) continue
            if (dictionary.containsEnPhrase(
                    normalized,
                    com.gamelingo.mobile.translate.local.EgyptianDictionary.normalizeEn(key),
                )
            ) {
                merged[key] = term.ar.trim()
            }
        }
        return merged
    }

    internal companion object {
        const val MAX_CONTEXT_LINES = 20

        /** Most lines kept waiting while the provider's wait is active; beyond this the oldest is dropped and counted. */
        const val BACKLOG_CAPACITY = 40

        /** A waiting line older than this is dropped and counted (visible in the diagnostics). */
        const val BACKLOG_MAX_AGE_MS = 180_000L

        /** Window of the "requests in the last minute" figure. */
        const val RECENT_WINDOW_MS = 60_000L

        /** A batch gets this much more time than one line (milliseconds per configured timeout second). */
        const val BATCH_TIMEOUT_FACTOR_MS = 1_500L

        /** Character budget for the context sent with one line: keeps the local model fast. */
        const val MAX_CONTEXT_CHARS = 400

        /** Gate findings that justify swapping in a known whole-line rendering. */
        val REPLACEABLE_ISSUES = setOf(
            com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue.PRONOUN_US_MISREAD,
            com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue.NEGATION_LOST,
            com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue.UNTRANSLATED,
            com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue.REGISTER_LEAK,
        )
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
