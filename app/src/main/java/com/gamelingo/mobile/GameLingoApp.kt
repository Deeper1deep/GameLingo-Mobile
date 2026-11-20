package com.gamelingo.mobile

import android.app.Application
import com.gamelingo.mobile.translate.TranslationCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GameLingoApp : Application() {

    @Inject
    lateinit var translationCoordinator: TranslationCoordinator

    override fun onCreate() {
        super.onCreate()
        // Starts listening for OCR lines so translations begin with the first capture.
        translationCoordinator.start()
    }
}
