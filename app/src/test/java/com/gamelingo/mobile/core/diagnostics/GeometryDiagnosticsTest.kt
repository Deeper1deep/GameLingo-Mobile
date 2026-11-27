package com.gamelingo.mobile.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryDiagnosticsTest {

    @Test fun `records lines in order and exports them with a header`() {
        GeometryDiagnostics.clear()
        GeometryDiagnostics.record("geometry: rebuilt gen=1 frame=1080x2400", now = 0L)
        GeometryDiagnostics.record("geometry: rebuilt gen=2 frame=2400x1080", now = 1000L)
        val text = GeometryDiagnostics.export("device=test")
        assertTrue(text.contains("device=test"))
        assertTrue(text.indexOf("gen=1") < text.indexOf("gen=2"))
        assertTrue(text.contains("lines=2"))
    }

    @Test fun `the buffer is capped and keeps the newest lines`() {
        GeometryDiagnostics.clear()
        repeat(GeometryDiagnostics.MAX_LINES + 50) { GeometryDiagnostics.record("line $it", now = 0L) }
        val lines = GeometryDiagnostics.snapshot()
        assertEquals(GeometryDiagnostics.MAX_LINES, lines.size)
        assertTrue(lines.last().endsWith("line ${GeometryDiagnostics.MAX_LINES + 49}"))
        assertTrue(lines.first().endsWith("line 50"))
        GeometryDiagnostics.clear()
    }
}
