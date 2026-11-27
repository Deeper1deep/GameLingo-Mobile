package com.gamelingo.mobile.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads Arabic translations aloud with the device's own text-to-speech engine
 * (no download from this app). Whether an Arabic voice exists depends on the
 * phone: when it does not, [speak] does nothing and [available] is false.
 */
class TtsSpeaker(context: Context) {
    @Volatile var available: Boolean = false
        private set
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext) { status ->
        val engine = tts
        if (status == TextToSpeech.SUCCESS && engine != null) {
            val r = engine.setLanguage(Locale("ar"))
            available = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /** Replaces whatever is being read: a new subtitle must not queue behind an old one. */
    fun speak(text: String, utteranceKey: String) {
        if (!available || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceKey)
    }

    fun stopSpeaking() { runCatching { tts?.stop() } }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        available = false
    }
}
