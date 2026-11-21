package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.translate.local.LocalAiRouter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/**
 * Routes translation between the online Gemini engine and the on-device ML
 * Kit engine according to the user's AI mode:
 *
 * - LOCAL: always the on-device chain (TranslateGemma HD -> Marian fast ->
 *   ML Kit fallback), never touches the network for translation.
 * - ONLINE: Gemini only. Missing key or cloud consent is a visible
 *   [OnlineNotConfiguredException], never a silent local translation - the
 *   user explicitly asked for online quality and must know it is not ready.
 * - AUTO with a local AI model installed (offline-first): the local chain is
 *   primary and does NOT wait for Gemini; an online fallback happens only
 *   when the user enabled it in settings AND the cloud gate is open.
 * - AUTO without a local AI model (legacy): Gemini when a key is configured
 *   and consent given, on-device otherwise; online failures fall back to the
 *   on-device engine and the history entry keeps the real engine id. After a
 *   quota (429) or availability (5xx) failure the online engine is skipped
 *   for [cooldownMs], so a burst of subtitle lines cannot hammer a spent quota.
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
    private val localChain: LocalAiRouter,
    private val settingsRepository: SettingsRepository,
) : TranslationEngine {

    override val engineId: String = ENGINE_ID

    /** Wall-clock time until which AUTO skips the online engine. */
    private val onlineCooldownUntil = AtomicLong(0)

    /** Injectable for tests. */
    var now: () -> Long = { System.currentTimeMillis() }

    /** Injectable for tests. */
    var cooldownMs: Long = DEFAULT_COOLDOWN_MS

    /** Extra random delay in [0, base/2]; tests inject { 0 } for exactness. */
    var jitter: (Long) -> Long = { base -> (0..base / 2).random() }

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

    /**
     * AUTO mode only: how long the online engine gets to answer alone before
     * the on-device engine starts in parallel and the first success wins. A
     * slow network must not hold a live subtitle hostage to the full request
     * timeout, and the local engine only burns cycles when the online path
     * is genuinely slow. Injectable for tests.
     */
    var autoSoftTimeoutMs: Long = DEFAULT_AUTO_SOFT_TIMEOUT_MS

    /** Engines warm up lazily on first use. */
    override suspend fun ensureReady() = Unit

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val settings = settingsRepository.settings.first()
        val cloudAllowed =
            settings.cloudTranslationOptIn && settings.onlineApiKey.isNotBlank()
        return when (settings.aiMode) {
            AiMode.LOCAL -> localChain.translate(request)
            AiMode.ONLINE ->
                if (cloudAllowed) {
                    translateOnlineWithRetry(request)
                } else {
                    throw OnlineNotConfiguredException()
                }
            AiMode.AUTO ->
                if (localChain.hasLocalAiModel()) {
                    translateAutoLocalFirst(request, settings, cloudAllowed)
                } else {
                    translateAuto(request, cloudAllowed = cloudAllowed)
                }
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
            AiMode.LOCAL -> localChain.plannedLocalEngineId()
            AiMode.ONLINE ->
                if (cloudAllowed) GeminiTranslationEngine.ENGINE_ID
                else localChain.plannedLocalEngineId() // never runs: throws instead
            AiMode.AUTO ->
                if (localChain.hasLocalAiModel()) {
                    localChain.plannedLocalEngineId()
                } else if (cloudAllowed && !inCooldown()) {
                    GeminiTranslationEngine.ENGINE_ID
                } else {
                    MlKitTranslationEngine.ENGINE_ID
                }
        }
    }

    /**
     * Offline-first AUTO: the local chain answers; only a chain-wide failure
     * (all on-device engines down for this line) may go online, and only when
     * the user explicitly enabled that policy. A Gemini failure here never
     * affects the local engines, and the local failure is never hidden.
     */
    private suspend fun translateAutoLocalFirst(
        request: TranslationRequest,
        settings: AppSettings,
        cloudAllowed: Boolean,
    ): TranslationResult {
        return try {
            localChain.translate(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (localError: Exception) {
            if (cloudAllowed && settings.autoOnlineFallback && !inCooldown()) {
                try {
                    translateOnlineWithRetry(request)
                } catch (onlineError: Exception) {
                    coolDownOnlineIfNeeded(onlineError)
                    throw onlineError
                }
            } else {
                throw localError
            }
        }
    }

    private fun inCooldown(): Boolean = now() < onlineCooldownUntil.get()

    /**
     * Quota spent or provider down: skip the online engine for a while
     * instead of retrying it on every subtitle line. A Retry-After hint
     * from the provider extends (never shortens) the cooldown.
     */
    private fun coolDownOnlineIfNeeded(error: Throwable?) {
        if (error !is QuotaExceededException && error !is ProviderUnavailableException) return
        val hintMs = (error as? QuotaExceededException)?.retryAfterMs ?: 0L
        onlineCooldownUntil.set(now() + maxOf(cooldownMs, hintMs))
    }

    private suspend fun translateAuto(
        request: TranslationRequest,
        cloudAllowed: Boolean,
    ): TranslationResult {
        if (!cloudAllowed || inCooldown()) return localEngine.translate(request)

        // Online starts immediately; the local engine joins after the soft
        // timeout and the first success wins. Online failures before that
        // point fall back exactly like before. Cancellation of the whole
        // scope (a newer subtitle line) cancels both engines at once.
        return coroutineScope {
            val onlineDeferred = async {
                try {
                    Result.success(translateOnlineWithRetry(request))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val localDeferred = async {
                delay(autoSoftTimeoutMs)
                try {
                    Result.success(localEngine.translate(request))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }

            val first = select<Result<TranslationResult>> {
                onlineDeferred.onAwait { it }
                localDeferred.onAwait { it }
            }
            first.fold(
                onSuccess = { winner ->
                    // An online failure recorded before the local win still
                    // counts: quota spent or provider down cools the online
                    // engine down even though the line was served on-device.
                    val onlineFailure =
                        if (onlineDeferred.isCompleted) {
                            onlineDeferred.getCompleted().exceptionOrNull()
                        } else {
                            null
                        }
                    coolDownOnlineIfNeeded(onlineFailure)
                    // Loser stops: an online win cancels local while it is
                    // still only waiting, so a fast online answer costs no
                    // on-device work at all.
                    onlineDeferred.cancel()
                    localDeferred.cancel()
                    winner
                },
                onFailure = {
                    // The first finisher failed: wait for the other engine.
                    val onlineResult = if (onlineDeferred.isCompleted) {
                        onlineDeferred.getCompleted()
                    } else {
                        localDeferred.cancel()
                        onlineDeferred.await()
                    }
                    onlineResult.fold(
                        onSuccess = { it },
                        onFailure = { onlineError ->
                            coolDownOnlineIfNeeded(onlineError)
                            localDeferred.await().getOrThrow()
                        },
                    )
                },
            )
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
                // Full jitter on the backoff: after an outage, every queued
                // subtitle retrying at the same instant re-hammers Gemini.
                sleep(backoffMs + jitter(backoffMs))
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
        localChain.close()
    }

    companion object {
        const val ENGINE_ID = "hybrid"
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_INITIAL_BACKOFF_MS = 500L
        const val BACKOFF_MULTIPLIER = 2L
        const val DEFAULT_AUTO_SOFT_TIMEOUT_MS = 1_500L
    }
}
