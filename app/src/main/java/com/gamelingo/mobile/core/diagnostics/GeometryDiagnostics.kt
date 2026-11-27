package com.gamelingo.mobile.core.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory record of the last geometry transitions (display, window,
 * resources, reported capture content, frame, transform, region pixels,
 * generation, migration). Capped, thread safe, no personal data: only sizes,
 * rotations and normalized fractions. Exported by the diagnostics screen so a
 * rotation failure on a real device can be inspected without logcat.
 */
object GeometryDiagnostics {
    const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun record(message: String, now: Long = System.currentTimeMillis()) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now))
        synchronized(lock) {
            lines.addLast("$stamp $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    /** The text file the user can share: header plus every recorded line, oldest first. */
    fun export(header: String): String {
        val body = snapshot()
        return buildString {
            appendLine("GameLingo geometry diagnostics")
            appendLine(header)
            appendLine("lines=${body.size}")
            appendLine()
            body.forEach { appendLine(it) }
        }
    }
}
