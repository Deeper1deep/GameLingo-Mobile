package com.gamelingo.mobile.core.translation

/**
 * What kind of subtitle a line is. Decides which instruction modules the
 * prompt carries and how long a faithful translation may be. Pure heuristics
 * on the text alone: no model call, no latency.
 */
enum class LineKind { UI_MESSAGE, OBJECTIVE, SHORT_DIALOGUE, NORMAL_DIALOGUE, LONG_DIALOGUE }

object LineClassifier {

    private val OBJECTIVE_VERB = Regex(
        """^(go to|reach|find|get to|follow|escort|protect|kill|defeat|collect|retrieve|destroy|steal|deliver|rescue|infiltrate|survive|escape|return to|talk to|meet|investigate|search|defend|capture|eliminate|hack|drive to|head to|use|explore|enter)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val UI_WORDS = Regex(
        """^(press|hold|tap|click|select|resume|pause|continue|options|settings|inventory|map|loading|saving|saved|checkpoint reached|game saved|new game|load game|quit|back|menu|confirm|cancel|accept|skip|retry|objective updated|mission (complete|failed)|level up|you died|game over|wasted|busted|\+\d+|\d+/\d+|\[.*])""",
        RegexOption.IGNORE_CASE,
    )

    const val SHORT_MAX_WORDS = 3
    const val LONG_MIN_WORDS = 18

    fun classify(text: String): LineKind {
        val t = text.trim()
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sentenceEnds = t.count { it == '.' || it == '!' || it == '?' }
        return when {
            words.isEmpty() -> LineKind.SHORT_DIALOGUE
            UI_WORDS.containsMatchIn(t) && sentenceEnds <= 1 && words.size <= 8 -> LineKind.UI_MESSAGE
            OBJECTIVE_VERB.containsMatchIn(t) && !t.endsWith("!") && !t.endsWith("?") &&
                words.size in 3..14 && !t.contains(",") -> LineKind.OBJECTIVE
            words.size <= SHORT_MAX_WORDS -> LineKind.SHORT_DIALOGUE
            words.size >= LONG_MIN_WORDS -> LineKind.LONG_DIALOGUE
            else -> LineKind.NORMAL_DIALOGUE
        }
    }

    /** True when the line looks cut off (OCR cropped it or the subtitle continues). */
    fun looksIncomplete(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.endsWith("...") || t.endsWith("…") || t.endsWith(",") || t.endsWith("-")) return true
        if (t.first().isLowerCase() && t.split(Regex("\\s+")).size >= 3) return true
        val last = t.trimEnd('.', '!', '?').substringAfterLast(' ').lowercase()
        return last in DANGLING_ENDINGS && t.last().isLetter()
    }

    private val DANGLING_ENDINGS = setOf(
        "the", "a", "an", "to", "of", "and", "or", "but", "with", "for", "in", "on", "at", "my", "your", "his", "her", "our", "their", "that", "if", "because",
    )
}

/** How long a faithful Arabic rendering may be relative to its source. */
object LengthPolicy {

    /**
     * Maximum plausible output/source character ratio per kind. Arabic is
     * normally within about 0.7-1.3x of English; beyond the cap the model
     * is padding, explaining or looping. Generous on purpose: a cap that
     * cuts real meaning would be worse than the padding it prevents.
     */
    fun maxRatio(kind: LineKind): Double = when (kind) {
        LineKind.UI_MESSAGE -> 2.5
        LineKind.SHORT_DIALOGUE -> 3.0
        LineKind.OBJECTIVE -> 2.2
        LineKind.NORMAL_DIALOGUE -> 2.0
        LineKind.LONG_DIALOGUE -> 1.8
    }

    /** Below this ratio a long source likely lost content (truncated output). */
    const val MIN_RATIO_FOR_LONG = 0.25

    fun tooLong(source: String, output: String, kind: LineKind): Boolean {
        if (source.isBlank()) return false
        val floor = 12 // tiny sources legitimately expand a lot
        return output.length > maxOf(floor.toDouble(), source.length * maxRatio(kind))
    }

    fun looksTruncated(source: String, output: String): Boolean {
        val words = source.trim().split(Regex("\\s+")).size
        return words >= 8 && output.trim().length < source.trim().length * MIN_RATIO_FOR_LONG
    }
}
