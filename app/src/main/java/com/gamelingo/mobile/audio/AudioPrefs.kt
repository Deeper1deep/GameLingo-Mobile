package com.gamelingo.mobile.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioSettings(
    /** Translate game speech. Off until the user turns it on. */
    val speechEnabled: Boolean = false,
    /** Read translations aloud with the device's text-to-speech. Off by default. */
    val ttsEnabled: Boolean = false,
    /** Download the speech model on Wi-Fi only. */
    val wifiOnlyDownload: Boolean = true,
)

/** Small separate store so existing settings, defaults and tests stay untouched. */
@Singleton
class AudioPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()

    private fun read() = AudioSettings(
        speechEnabled = prefs.getBoolean("speech", false),
        ttsEnabled = prefs.getBoolean("tts", false),
        wifiOnlyDownload = prefs.getBoolean("wifi_only", true),
    )

    fun update(change: (AudioSettings) -> AudioSettings) {
        val next = change(_settings.value)
        prefs.edit().putBoolean("speech", next.speechEnabled).putBoolean("tts", next.ttsEnabled)
            .putBoolean("wifi_only", next.wifiOnlyDownload).apply()
        _settings.value = next
    }
}
