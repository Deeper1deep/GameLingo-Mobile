package com.gamelingo.mobile.core.diagnostics

/**
 * Bounded in-memory trace of translation outcomes. METADATA ONLY: stage,
 * engine id, text LENGTHS, issue names, retry/reject flags and timings.
 * It never stores source or translated text, never touches disk and is
 * never uploaded. Lost when the process ends.
 */
object PipelineTrace {

    data class Event(
        val atMillis: Long,
        val subtitleId: Long,
        val outcome: String,
        val engineId: String,
        val sourceChars: Int,
        val outputChars: Int,
        val issues: List<String>,
        val retried: Boolean,
        val fromCache: Boolean,
        val engineMillis: Long,
    )

    const val CAPACITY = 200

    private val events = ArrayDeque<Event>()

    fun record(event: Event) = synchronized(events) {
        events.addLast(event)
        while (events.size > CAPACITY) events.removeFirst()
    }

    fun snapshot(): List<Event> = synchronized(events) { events.toList() }

    fun clear() = synchronized(events) { events.clear() }

    /** Plain-text summary for sharing. Contains no subtitle content. */
    fun export(): String = buildString {
        appendLine("GameLingo pipeline trace (metadata only, no subtitle text)")
        for (e in snapshot()) {
            appendLine(
                "t=${e.atMillis} id=${e.subtitleId} ${e.outcome} engine=${e.engineId} " +
                    "src=${e.sourceChars}c out=${e.outputChars}c ms=${e.engineMillis} " +
                    "retry=${e.retried} cache=${e.fromCache} issues=${e.issues.joinToString(",")}",
            )
        }
    }
}
