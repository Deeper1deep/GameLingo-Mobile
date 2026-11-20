package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.SettingsRepository
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
 * - ONLINE: Gemini only; failures surface to the user instead of silently
 *   degrading (he picked online explicitly).
 * - AUTO: Gemini when a key is configured, on-device otherwise; any online
 *   failure (no connection, quota, bad key) falls back to the on-device
 *   engine and the history entry keeps the real engine id. Cancellation is
 *   never treated as a failure: a newer subtitle line simply wins.
 *
 * Privacy gate: the online engine is only ever called when the user has both
 * entered a key AND explicitly accepted the cloud explainer
 * ([com.gamelingo.mobile.data.settings.AppSettings.cloudTranslationOptIn]).
 * Until then every mode resolves to the on-device engine, so no recognized
 * text can leave the device by accident.
 */
@Singleton
class HybridTranslationEngine @Inject constructor(
    @Named("online") private val onlineEngine: TranslationEngine,
    @Named("local") private val localEngine: TranslationEngine,
    private val settingsRepository: SettingsRepository,
) : TranslationEngine {

    override val engineId: String = ENGINE_ID

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
                    localEngine.translate(request)
                }
            AiMode.AUTO -> translateAuto(request, cloudAllowed = cloudAllowed)
        }
    }

    private suspend fun translateAuto(
        request: TranslationRequest,
        cloudAllowed: Boolean,
    ): TranslationResult {
        if (!cloudAllowed) return localEngine.translate(request)
        return try {
            onlineEngine.translate(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            localEngine.translate(request)
        }
    }

    override fun close() {
        onlineEngine.close()
        localEngine.close()
    }

    companion object {
        const val ENGINE_ID = "hybrid"
    }
}
