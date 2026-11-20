package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Routes translation between the online Gemini engine and the on-device ML
 * Kit engine according to the user's AI mode:
 *
 * - LOCAL: always the on-device engine, never touches the network.
 * - ONLINE: Gemini only. Missing key or cloud consent is a visible
 *   [OnlineNotConfiguredException], never a silent local translation - the
 *   user explicitly asked for online quality and must know it is not ready.
 * - AUTO: Gemini when a key is configured and consent given, on-device
 *   otherwise; online failures fall back to the on-device engine and the
 *   history entry keeps the real engine id. After a quota (429) or
 *   availability (5xx) failure the online engine is skipped for
 *   [cooldownMs], so a burst of subtitle lines cannot hammer a spent quota.
 *
 * Cancellation is never treated as a failure: a newer subtitle line simply
 * wins. Privacy gate: the online engine only runs when the user both entered
 * a key AND accepted the cloud explainer
 * ([AppSettings.cloudTranslationOptIn]).
 */
@Singleton
class HybridTranslationEngine @Inject constructor(
    @Named("online") private val onlineEngine: TranslationEngine,
    @Named("local") private val localEngine: TranslationEngine,
    private val settingsRepository: SettingsRepository,
) : TranslationEngine {

    override val engineId: String = ENGINE_ID

    /** Wall-clock time until which AUTO skips the online engine. */
    private val onlineCooldownUntil = AtomicLong(0)

    /** Injectable for tests. */
    var now: () -> Long = { System.currentTimeMillis() }

    /** Injectable for tests. */
    var cooldownMs: Long = DEFAULT_COOLDOWN_MS

    /** Engines warm up lazily on first use. */
    override suspend fun ensureReady() = Unit

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val settings = settingsRepository.settings.first()
        val cloudAllowed =
            settings.cloudTranslationOptIn && settings.onlineApiKey.isNotBlank()
        return when (settings.aiMode) {
            AiMode.LOCAL -> localEngine.translate(request)
            AiMode.ONLINE ->
                if (cloudAllowed) {
                    onlineEngine.translate(request)
                } else {
                    throw OnlineNotConfiguredException()
                }
            AiMode.AUTO -> translateAuto(request, cloudAllowed = cloudAllowed)
        }
    }

    /**
     * The engine the next request would run on under current settings. The
     * translation cache keys on this so a mode or readiness change never
     * serves an entry from a different effective configuration.
     */
    suspend fun plannedEngineId(): String {
        val settings = settingsRepository.settings.first()
        return plannedEngineId(settings)
    }

    private fun plannedEngineId(settings: AppSettings): String {
        val cloudAllowed =
            settings.cloudTranslationOptIn && settings.onlineApiKey.isNotBlank()
        return when (settings.aiMode) {
            AiMode.LOCAL -> MlKitTranslationEngine.ENGINE_ID
            AiMode.ONLINE ->
                if (cloudAllowed) GeminiTranslationEngine.ENGINE_ID
                else MlKitTranslationEngine.ENGINE_ID // never runs: throws instead
            AiMode.AUTO ->
                if (cloudAllowed && !inCooldown()) GeminiTranslationEngine.ENGINE_ID
                else MlKitTranslationEngine.ENGINE_ID
        }
    }

    private fun inCooldown(): Boolean = now() < onlineCooldownUntil.get()

    private suspend fun translateAuto(
        request: TranslationRequest,
        cloudAllowed: Boolean,
    ): TranslationResult {
        if (!cloudAllowed || inCooldown()) return localEngine.translate(request)
        return try {
            onlineEngine.translate(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is QuotaExceededException || error is ProviderUnavailableException) {
                // Quota spent or provider down: skip the online engine for a
                // while instead of retrying it on every subtitle line.
                onlineCooldownUntil.set(now() + cooldownMs)
            }
            localEngine.translate(request)
        }
    }

    override fun close() {
        onlineEngine.close()
        localEngine.close()
    }

    companion object {
        const val ENGINE_ID = "hybrid"
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
