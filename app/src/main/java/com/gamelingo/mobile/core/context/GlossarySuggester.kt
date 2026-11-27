package com.gamelingo.mobile.core.context

/**
 * Proposes glossary terms (character and place names, factions) from recent
 * dialogue. It only PROPOSES: nothing is saved until the user confirms in the
 * glossary screen, and the user supplies the Arabic rendering themselves.
 *
 * A candidate is a capitalised word (or run of capitalised words) that appears
 * in the MIDDLE of a sentence at least [minOccurrences] times, is not a common
 * word, is not already in the glossary and was not dismissed. Sentence-initial
 * capitals are ignored because every sentence starts with one.
 */
object GlossarySuggester {
    const val MIN_OCCURRENCES = 2
    const val MAX_SUGGESTIONS = 5

    private val COMMON = setOf(
        "I", "I'm", "I'll", "I've", "I'd", "OK", "Okay", "Yes", "No", "God", "Mr", "Mrs", "Ms", "Dr",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December",
    )
    private val CAP_RUN = Regex("""\b[A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,})*\b""")

    fun suggest(
        recentLines: List<String>,
        existing: Collection<String>,
        dismissed: Collection<String> = emptyList(),
        minOccurrences: Int = MIN_OCCURRENCES,
    ): List<String> {
        val known = (existing + dismissed).map { it.trim().lowercase() }.toSet()
        val counts = LinkedHashMap<String, Int>()
        for (line in recentLines) {
            for (m in CAP_RUN.findAll(line)) {
                var term = m.value
                if (isSentenceStart(line, m.range.first)) {
                    // The first capital is just the sentence opener: keep only the rest of a longer run.
                    val rest = term.substringAfter(' ', "")
                    if (rest.isEmpty()) continue
                    term = rest
                }
                if (term in COMMON) continue
                counts[term] = (counts[term] ?: 0) + 1
            }
        }
        return counts.entries
            .filter { it.value >= minOccurrences && it.key.lowercase() !in known }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(MAX_SUGGESTIONS)
    }

    private fun isSentenceStart(line: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && line[i] == ' ') i--
        if (i < 0) return true
        return line[i] in ".!?\"“”'…"
    }
}

/** Session-only, in-memory pool of recent source lines and dismissed suggestions. Never persisted. */
object SuggestionPool {
    private const val MAX_LINES = 120
    private val lines = ArrayDeque<String>()
    private val dismissed = mutableSetOf<String>()

    @Synchronized fun record(line: String) {
        if (line.isBlank()) return
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized fun dismiss(term: String) { dismissed += term }

    @Synchronized fun suggestions(existing: Collection<String>): List<String> =
        GlossarySuggester.suggest(lines.toList(), existing, dismissed)

    @Synchronized fun clear() { lines.clear(); dismissed.clear() }
}
