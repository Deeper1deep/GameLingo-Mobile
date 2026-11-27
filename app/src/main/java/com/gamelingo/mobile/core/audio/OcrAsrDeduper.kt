package com.gamelingo.mobile.core.audio

/**
 * Stops the same dialogue being translated twice when it arrives both as
 * on-screen text (OCR) and as recognised speech (ASR). Compares normalised
 * word sets; a near match inside [windowMillis] is a duplicate. Different
 * lines are never merged, and a line with too few words is never treated as
 * a duplicate (short interjections repeat legitimately).
 */
class OcrAsrDeduper(private val windowMillis: Long = 6_000L, private val minWords: Int = 3) {

    private data class Seen(val words: Set<String>, val atMillis: Long, val fromAudio: Boolean)

    private val recent = ArrayDeque<Seen>()

    fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }.toSet()

    /** True when [text] from this source duplicates a line from the OTHER source just before. */
    @Synchronized
    fun isDuplicate(text: String, fromAudio: Boolean, nowMillis: Long): Boolean {
        while (recent.isNotEmpty() && nowMillis - recent.first().atMillis > windowMillis) recent.removeFirst()
        val w = words(text)
        val dup = w.size >= minWords && recent.any {
            it.fromAudio != fromAudio && jaccard(it.words, w) >= 0.7
        }
        recent.addLast(Seen(w, nowMillis, fromAudio))
        if (recent.size > 32) recent.removeFirst()
        return dup
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }
}
