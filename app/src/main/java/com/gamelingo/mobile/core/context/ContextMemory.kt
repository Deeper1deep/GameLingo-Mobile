package com.gamelingo.mobile.core.context

/**
 * Short, bounded memory of recent dialogue lines used as translation
 * context. Two safeguards keep it honest:
 *
 * - Bounded: only the last [maxLines] lines are kept.
 * - Scene separation: when lines carry timestamps and the gap between two
 *   consecutive lines exceeds [sceneGapMillis], the conversation clearly
 *   paused (scene change, cutscene, menu), so the old context would mislead
 *   the next translation more than help it; the memory resets instead of
 *   mixing scenes. [add] reports whether a new scene started so callers can
 *   drop anything derived from the old scene.
 */
class ContextMemory(
    private val maxLines: Int,
    private val sceneGapMillis: Long = DEFAULT_SCENE_GAP_MILLIS,
) {

    /** Where a remembered line came from. Known at ingress; no speaker recogniser is involved. */
    enum class Source { OCR, AUDIO, UNKNOWN }

    private data class Entry(
        val line: String,
        val atMillis: Long?,
        val source: Source,
        val translated: String? = null,
        val subtitleId: Long = 0L,
        val dialect: String? = null,
    ) {
        val isQuestion: Boolean get() = line.trimEnd().endsWith("?")
    }

    /** Public, read-only view of one remembered dialogue entry. Speaker is not recorded (no recogniser). */
    data class ContextEntry(
        val original: String,
        val translated: String?,
        val subtitleId: Long,
        val dialect: String?,
        val atMillis: Long?,
        val source: Source,
        val isQuestion: Boolean,
    )

    private val entries = ArrayDeque<Entry>()

    /** Timestamp-less add (legacy callers): never triggers a scene break. */
    @Synchronized
    fun add(line: String) {
        add(line, null, Source.UNKNOWN)
    }

    /**
     * Adds [line] observed at [atMillis]. When the gap since the previous
     * timestamped line exceeds the scene gap, the old context is dropped
     * first. Returns true when a new scene started.
     */
    @Synchronized
    fun add(
        line: String,
        atMillis: Long,
        source: Source = Source.UNKNOWN,
        translated: String? = null,
        subtitleId: Long = 0L,
        dialect: String? = null,
    ): Boolean = add(line, atMillis as Long?, source, translated, subtitleId, dialect)

    @Synchronized
    private fun add(
        line: String,
        atMillis: Long?,
        source: Source,
        translated: String? = null,
        subtitleId: Long = 0L,
        dialect: String? = null,
    ): Boolean {
        if (maxLines <= 0 || line.isBlank()) return false
        var newScene = false
        val previous = entries.lastOrNull()
        if (atMillis != null && previous?.atMillis != null &&
            atMillis - previous.atMillis > sceneGapMillis
        ) {
            entries.clear()
            newScene = true
        }
        if (entries.lastOrNull()?.line == line) return newScene
        entries.addLast(Entry(line, atMillis, source, translated, subtitleId, dialect))
        while (entries.size > maxLines) {
            entries.removeFirst()
        }
        return newScene
    }

    /** Full entries, oldest first. */
    @Synchronized
    fun entriesSnapshot(): List<ContextEntry> = entries.map {
        ContextEntry(it.line, it.translated, it.subtitleId, it.dialect, it.atMillis, it.source, it.isQuestion)
    }

    /** Origin of each remembered line, oldest first (same order as [snapshot]). */
    @Synchronized
    fun sources(): List<Source> = entries.map { it.source }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = entries.map { it.line }

    /**
     * The context to send with [current]: bounded by [maxLines] and [maxChars],
     * newest first when trimming, returned oldest first.
     *
     * - The previous line is always eligible: replies ("I don't know.") only
     *   make sense next to the question before them.
     * - Older lines are included only when they share a name or a content
     *   word with [current], so unrelated chatter does not steer the line.
     * - Menu text and mission objectives never count as dialogue context.
     */
    @Synchronized
    fun select(current: String, maxLines: Int, maxChars: Int, currentSource: Source = Source.UNKNOWN): List<String> {
        if (maxLines <= 0 || maxChars <= 0) return emptyList()
        val currentWords = contentWords(current)
        val picked = ArrayDeque<String>()
        var used = 0
        var distance = 0
        for (entry in entries.reversed()) {
            distance++
            val kind = com.gamelingo.mobile.core.translation.LineClassifier.classify(entry.line)
            if (kind == com.gamelingo.mobile.core.translation.LineKind.UI_MESSAGE ||
                kind == com.gamelingo.mobile.core.translation.LineKind.OBJECTIVE
            ) continue
            val words = contentWords(entry.line)
            // The same sentence seen through the other channel (heard vs read) is not earlier
            // context, it is the current line again. The 0.8 overlap is my choice, unmeasured.
            if (currentSource != Source.UNKNOWN && entry.source != Source.UNKNOWN &&
                entry.source != currentSource && isSameSentence(words, currentWords)
            ) continue
            // A question one line back still frames the reply after an interjection.
            val related = distance == 1 || (distance == 2 && entry.isQuestion) ||
                words.any { it in currentWords }
            if (!related) continue
            if (picked.size >= maxLines) break
            val cost = entry.line.length
            if (used + cost > maxChars) {
                // The newest line is kept even when it alone exceeds the budget, trimmed.
                if (picked.isEmpty()) { picked.addFirst(entry.line.take(maxChars)); used = maxChars }
                break
            }
            picked.addFirst(entry.line)
            used += cost
        }
        return picked.toList()
    }

    private fun isSameSentence(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.count { it in b }.toDouble()
        return shared / (a.size + b.size - shared) >= SAME_SENTENCE_OVERLAP
    }

    private fun contentWords(text: String): Set<String> =
        text.split(NON_WORD).map { it.lowercase() }
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .toSet()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        private const val SAME_SENTENCE_OVERLAP = 0.8
        private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")
        private val STOP_WORDS = setOf(
            "that", "this", "with", "have", "from", "they", "what", "your", "will", "just",
            "there", "their", "about", "would", "could", "should", "been", "were", "then",
            "than", "them", "into", "here", "when", "where", "which", "know", "dont", "don't",
        )

        /** A pause longer than a minute means the next line starts a new scene. */
        const val DEFAULT_SCENE_GAP_MILLIS = 60_000L
    }
}
