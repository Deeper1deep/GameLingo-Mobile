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

    private data class Entry(val line: String, val atMillis: Long?)

    private val entries = ArrayDeque<Entry>()

    /** Timestamp-less add (legacy callers): never triggers a scene break. */
    @Synchronized
    fun add(line: String) {
        add(line, null)
    }

    /**
     * Adds [line] observed at [atMillis]. When the gap since the previous
     * timestamped line exceeds the scene gap, the old context is dropped
     * first. Returns true when a new scene started.
     */
    @Synchronized
    fun add(line: String, atMillis: Long): Boolean = add(line, atMillis as Long?)

    @Synchronized
    private fun add(line: String, atMillis: Long?): Boolean {
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
        entries.addLast(Entry(line, atMillis))
        while (entries.size > maxLines) {
            entries.removeFirst()
        }
        return newScene
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = entries.map { it.line }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** A pause longer than a minute means the next line starts a new scene. */
        const val DEFAULT_SCENE_GAP_MILLIS = 60_000L
    }
}
