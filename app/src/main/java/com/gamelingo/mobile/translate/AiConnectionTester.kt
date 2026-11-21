package com.gamelingo.mobile.translate

import android.os.SystemClock
import com.gamelingo.mobile.data.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Outcome of the user-initiated "test connection" button on the AI Engine screen. */
sealed interface AiTestResult {
    data class Success(val elapsedMs: Long) : AiTestResult
    data object NoKey : AiTestResult
    data object NoConsent : AiTestResult
    data object BadKey : AiTestResult
    data object Quota : AiTestResult
    data object Unavailable : AiTestResult
    data object Timeout : AiTestResult
    data object Network : AiTestResult
    data object Unknown : AiTestResult
}

/**
 * Sends one tiny real request ("Hello") through the existing online engine so
 * the user can see whether the key, the consent and the network actually work.
 * It only runs when the user taps the button, and never without the cloud
 * consent. It reuses the engine as is: no engine or prompt logic lives here.
 */
@Singleton
class AiConnectionTester @Inject constructor(
    @Named("online") private val onlineEngine: TranslationEngine,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun test(): AiTestResult {
        val settings = settingsRepository.settings.first()
        if (settings.onlineApiKey.isBlank()) return AiTestResult.NoKey
        if (!settings.cloudTranslationOptIn) return AiTestResult.NoConsent
        val started = SystemClock.elapsedRealtime()
        return try {
            withTimeout(settings.requestTimeoutSec * 1000L) {
                onlineEngine.translate(TranslationRequest(text = "Hello"))
            }
            AiTestResult.Success(SystemClock.elapsedRealtime() - started)
        } catch (_: TimeoutCancellationException) {
            AiTestResult.Timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OnlineNotConfiguredException) {
            AiTestResult.NoKey
        } catch (_: ApiAuthException) {
            AiTestResult.BadKey
        } catch (_: QuotaExceededException) {
            AiTestResult.Quota
        } catch (_: ProviderUnavailableException) {
            AiTestResult.Unavailable
        } catch (_: IOException) {
            AiTestResult.Network
        } catch (_: Exception) {
            AiTestResult.Unknown
        }
    }
}
