package com.gamelingo.mobile.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStyleMaxLinesTest {

    @Test fun `no limit by default`() {
        assertEquals(0, OverlayStyle().maxLines)
        assertEquals(Int.MAX_VALUE, OverlayStyle().maxLinesOrUnlimited())
    }

    @Test fun `limit is clamped and old stored styles keep working`() {
        assertEquals(OverlayStyle.MAX_LINES, OverlayStyle(maxLines = 99).sanitized().maxLines)
        assertEquals(0, OverlayStyle(maxLines = -3).sanitized().maxLines)
        // A style saved before this setting existed has no maxLines key.
        val old = OverlayStyle.decodeOrDefault("""{"bold":false,"cornerRadiusDp":20}""")
        assertEquals(0, old.maxLines)
        assertEquals(20, old.cornerRadiusDp)
    }

    @Test fun `placement diagnostics explain a fallback`() {
        val (p1, why1) = TranslationPlacement.decodeWithDiagnostic("{broken")
        assertEquals(TranslationPlacement(), p1)
        assertTrue(why1!!.contains("corrupt"))
        val (p2, why2) = TranslationPlacement.decodeWithDiagnostic("""{"fixedX":4.0,"fixedY":0.5}""")
        assertEquals(1f, p2.fixedX!!, 0f)
        assertTrue(why2!!.contains("clamped"))
        assertEquals(null, TranslationPlacement.decodeWithDiagnostic(null).second)
        val fine = TranslationPlacement.encode(TranslationPlacement(fixedX = 0.2f, fixedY = 0.3f))
        assertEquals(null, TranslationPlacement.decodeWithDiagnostic(fine).second)
    }
}
