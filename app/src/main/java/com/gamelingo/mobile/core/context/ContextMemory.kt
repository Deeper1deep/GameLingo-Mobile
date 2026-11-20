package com.gamelingo.mobile.core.context

/**
 * Short rolling memory of recent original lines. The brief forbids sending the
 * whole dialogue with every request, so this keeps only the last [maxLines] lines,
 * oldest first. Thread-safe; the capture service and the coordinator run on
 * different threads.
 */
class ContextMemory(private val maxLines: Int) {

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (maxLines <= 0 || line.isBlank()) return
        if (lines.lastOrNull() == line) return
        lines.addLast(line)
        while (lines.size > maxLines) {
            lines.removeFirst()
        }
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun size(): Int = lines.size
}
