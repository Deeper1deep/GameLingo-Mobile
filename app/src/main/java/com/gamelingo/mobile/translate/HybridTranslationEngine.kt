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

    /**
     * Bounded retry for transient online failures (network drops, 5xx): a
     * short burst of subtitles must not lose lines to one flaky moment, and
     * a dead provider must not be hammered either. Quota (429) and key (401/
     * 403) failures never retry: waiting cannot fix them. Injectable for
     * tests.
     */
    var maxRetries: Int = DEFAULT_MAX_RETRIES
    var initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS
    var sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }

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
                    translateOnlineWithRetry(request)
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
            translateOnlineWithRetry(request)
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

    /**
     * Runs the online engine with bounded exponential backoff for transient
     * failures only. Cancellation always propagates untouched, so a newer
     * subtitle line still wins instantly and is never misreported as a
     * network error.
     */
    private suspend fun translateOnlineWithRetry(request: TranslationRequest): TranslationResult {
        var attempt = 0
        var backoffMs = initialBackoffMs
        while (true) {
            try {
                return onlineEngine.translate(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isTransient(error) || attempt >= maxRetries) throw error
                attempt++
                sleep(backoffMs)
                backoffMs *= BACKOFF_MULTIPLIER
            }
        }
    }

    /** Quota and key errors are final; network and 5xx errors can heal. */
    private fun isTransient(error: Exception): Boolean = when (error) {
        is QuotaExceededException, is ApiAuthException, is OnlineNotConfiguredException -> false
        is ProviderUnavailableException -> true
        is java.io.IOException -> true
        else -> false
    }

    override fun close() {
        onlineEngine.close()
        localEngine.close()
    }

    companion object {
        const val ENGINE_ID = "hybrid"
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_INITIAL_BACKOFF_MS = 500L
        const val BACKOFF_MULTIPLIER = 2L
    }
}
