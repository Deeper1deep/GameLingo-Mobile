package com.gamelingo.mobile

import android.app.Application
import com.gamelingo.mobile.core.timing.PipelineTiming
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.translate.TranslationCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GameLingoApp : Application() {

    @Inject
    lateinit var translationCoordinator: TranslationCoordinator

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Per-stage latency logging stays silent in release builds.
        PipelineTiming.enabled = BuildConfig.DEBUG
        // Starts listening for OCR lines so translations begin with the first capture.
        translationCoordinator.start()
        // One-time move of a legacy plaintext API key into encrypted storage.
        appScope.launch {
            runCatching { settingsRepository.migrateApiKeyIfNeeded() }
        }
    }
}
