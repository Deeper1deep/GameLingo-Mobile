package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.context.ContextMemory
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.TranslationSessionBus
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Watches the session bus for accepted OCR lines, translates them with the
 * hybrid engine, publishes the translation back to the bus and records it in
 * the Room history.
 *
 * Honours the user's settings on every line: AI mode and API key (inside the
 * hybrid engine), request timeout, cache enablement and context line count.
 * The cache key covers the full translation context (mode, planned engine,
 * prompt version, dialogue context, language pair), so a mode switch or a
 * prompt upgrade never serves a stale answer. A newer line cancels the
 * in-flight translation so a stale subtitle never replaces a fresher one;
 * cancelled work is never shown as a failure.
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val contextMemory = ContextMemory(maxLines = MAX_CONTEXT_LINES)
    private var currentJob: Job? = null

    /** Idempotent: safe to call from every service start and app launch. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            TranslationSessionBus.latestLine.collect { line ->
                if (line == null) return@collect
                currentJob?.cancel()
                currentJob = scope.launch {
                    translateLine(line.text, line.capturedAtMillis)
                }
            }
        }
    }

    private suspend fun translateLine(text: String, capturedAtMillis: Long) {
        val settings = settingsRepository.settings.first()
        val contextLines = contextMemory.snapshot().takeLast(settings.contextLines)
        val cacheContext = CacheContext(
            aiMode = settings.aiMode,
            plannedEngineId = hybridEngine.plannedEngineId(),
            promptVersion = EgyptianDialectPrompt.PROMPT_VERSION,
            contextSignature = CacheContext.signatureOf(contextLines),
        )

        if (settings.cacheEnabled) {
            cache.get(text, cacheContext)?.let { hit ->
                publish(
                    original = text,
                    result = TranslationResult(
                        translatedText = hit.translatedText,
                        engineId = hit.engineId,
                        fromCache = true,
                    ),
                    capturedAtMillis = capturedAtMillis,
                )
                contextMemory.add(text)
                return
            }
        }

        runCatching {
            withTimeout(settings.requestTimeoutSec * MILLIS_PER_SECOND) {
                hybridEngine.translate(
                    TranslationRequest(
                        text = text,
                        contextLines = contextLines,
                    ),
                )
            }
        }.onSuccess { result ->
            if (settings.cacheEnabled) {
                cache.put(
                    text,
                    cacheContext,
                    CachedTranslation(
                        translatedText = result.translatedText,
                        engineId = result.engineId,
                    ),
                )
            }
            publish(text, result, capturedAtMillis)
            contextMemory.add(text)
            publishEngineNotice(settings.aiMode, result.engineId)
        }.onFailure { error ->
            when (error) {
                // The provider hit the user's timeout: a visible failure.
                is TimeoutCancellationException ->
                    TranslationSessionBus.onTranslationFailed(text)
                // ONLINE selected without key/consent: say so, do not fake it.
                is OnlineNotConfiguredException -> {
                    TranslationSessionBus.onNotice(SessionNotice.ONLINE_NOT_CONFIGURED)
                    TranslationSessionBus.onTranslationFailed(text)
                }
                // A newer line superseded this one: stay silent.
                is CancellationException -> throw error
                else -> TranslationSessionBus.onTranslationFailed(text)
            }
        }
    }

    /** Tell the user when the engine that answered is not the one they picked. */
    private fun publishEngineNotice(mode: AiMode, actualEngineId: String) {
        if (mode != AiMode.LOCAL && actualEngineId == MlKitTranslationEngine.ENGINE_ID) {
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

    private companion object {
        const val MAX_CONTEXT_LINES = 20
        const val MILLIS_PER_SECOND = 1_000L
    }
}
