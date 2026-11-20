package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.context.ContextMemory
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import com.gamelingo.mobile.data.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches the session bus for accepted OCR lines, translates them with the
 * engine selected by the current settings, publishes the translation back to
 * the bus and records it in the Room history.
 *
 * Phase 4 ships the on-device engine only; the online provider is wired in
 * Phase 5 once the user picks it, so AUTO/ONLINE currently resolve to the
 * on-device engine and the history entry says exactly what ran.
 */
@Singleton
class TranslationCoordinator @Inject constructor(
    private val localEngine: MlKitTranslationEngine,
    private val settingsRepository: SettingsRepository,
    private val historyDao: TranslationHistoryDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val contextMemory = ContextMemory(maxLines = MAX_CONTEXT_LINES)

    /** Idempotent: safe to call from every service start and app launch. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            TranslationSessionBus.latestLine.collect { line ->
                if (line == null) return@collect
                translateLine(line.text, line.capturedAtMillis)
            }
        }
    }

    private suspend fun translateLine(text: String, capturedAtMillis: Long) {
        val settings = settingsRepository.settings.first()
        val engine = selectEngine()
        runCatching {
            engine.translate(
                TranslationRequest(
                    text = text,
                    contextLines = contextMemory.snapshot()
                        .takeLast(settings.contextLines),
                ),
            )
        }.onSuccess { result ->
            TranslationSessionBus.onTranslated(
                original = text,
                translated = result.translatedText,
                engineId = result.engineId,
                fromCache = result.fromCache,
                capturedAtMillis = capturedAtMillis,
            )
            historyDao.insert(
                TranslationHistoryEntity(
                    sourceText = text,
                    translatedText = result.translatedText,
                    engine = result.engineId,
                    wasCached = result.fromCache,
                    createdAt = capturedAtMillis,
                ),
            )
            contextMemory.add(text)
        }.onFailure {
            TranslationSessionBus.onTranslationFailed(text)
        }
    }

    /**
     * The only engine available in Phase 4. Phase 5 returns the online engine
     * here for ONLINE/AUTO when the user's key is configured.
     */
    private fun selectEngine(): TranslationEngine = localEngine

    private companion object {
        const val MAX_CONTEXT_LINES = 20
    }
}
