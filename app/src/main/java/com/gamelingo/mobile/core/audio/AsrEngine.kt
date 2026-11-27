package com.gamelingo.mobile.core.audio

/** One recognition result for the utterance in progress. */
data class AsrUpdate(val text: String, val isFinal: Boolean)

/**
 * A speech recogniser that consumes 16 kHz mono 16-bit PCM frames of ONE
 * utterance at a time. Implementations must tolerate [reset] and [close]
 * being called at any point, more than once.
 */
interface AsrEngine {
    /** Feeds a frame; returns the current partial text, or null when nothing changed. */
    fun acceptFrame(pcm: ShortArray): AsrUpdate?

    /** Ends the utterance and returns its final text (possibly empty). Resets for the next one. */
    fun finish(): String

    /** Drops the utterance in progress. */
    fun reset()

    fun close()
}

/** Extracts the recognised text from the JSON the recogniser returns. */
object AsrJson {
    fun textOf(json: String?): String {
        if (json.isNullOrBlank()) return ""
        val m = TEXT_FIELD.find(json) ?: return ""
        return unescape(m.groupValues[2]).trim()
    }

    private val TEXT_FIELD = Regex("\"(text|partial)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 't', 'r' -> { out.append(' '); i += 2 }
                    'u' -> if (i + 5 < s.length) {
                        out.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 6
                    } else { i += 2 }
                    else -> { out.append(n); i += 2 }
                }
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
