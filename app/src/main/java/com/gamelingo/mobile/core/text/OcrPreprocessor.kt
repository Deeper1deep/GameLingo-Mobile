package com.gamelingo.mobile.core.text

/**
 * Conservative clean-up of OCR text BEFORE translation. It is separate from
 * translation on purpose, and it only does changes that are safe on their
 * own: whitespace, doubled-up fragments, stray edge symbols and the classic
 * "|" read instead of "I". Anything uncertain (a possible name, a number, a
 * word that might be real) is left exactly as read.
 */
object OcrPreprocessor {

    data class Result(val text: String, val changes: List<String>)

    private val WS = Regex("\\s+")
    // A lone vertical bar standing where the pronoun I fits: "| think", "you and |".
    private val BAR_AS_I = Regex("""(?<![\p{L}\p{N}|])\|(?![\p{L}\p{N}|])(?=\s+\p{L}|\s*[.,!?]|$)""")
    private val EDGE_JUNK = Regex("""^[\s|_~^`\\<>]+|[\s|_~^`\\<>]+$""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.!?;:])""")

    fun clean(raw: String): Result {
        val changes = mutableListOf<String>()
        var t = raw

        val ws = t.replace(WS, " ").trim()
        if (ws != t) { changes += "whitespace"; t = ws }

        val edge = t.replace(EDGE_JUNK, "")
        if (edge != t && edge.isNotEmpty()) { changes += "edge-symbols"; t = edge }

        val bar = if (t.contains('|')) BAR_AS_I.replace(t, "I") else t
        if (bar != t) { changes += "bar-as-I"; t = bar }

        val sp = t.replace(SPACE_BEFORE_PUNCT, "$1")
        if (sp != t) { changes += "space-before-punctuation"; t = sp }

        val dedup = collapseRepeatedFragment(t)
        if (dedup != t) { changes += "repeated-fragment"; t = dedup }

        return Result(t, changes)
    }

    /**
     * "Get in the car Get in the car" -> "Get in the car": the same subtitle
     * captured twice in one frame. Only exact doubling of at least three
     * words (case-insensitive) is collapsed; a short echo ("No no no") is
     * real speech and stays.
     */
    internal fun collapseRepeatedFragment(text: String): String {
        val words = text.split(" ")
        if (words.size < 6 || words.size % 2 != 0) return text
        val half = words.size / 2
        val a = words.subList(0, half).joinToString(" ")
        val b = words.subList(half, words.size).joinToString(" ")
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() || it == ' ' } }
        return if (norm(a) == norm(b)) a else text
    }
}
