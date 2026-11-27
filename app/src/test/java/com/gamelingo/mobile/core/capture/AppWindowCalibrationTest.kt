package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.overlay.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWindowCalibrationTest {
    private val cal = AppWindowCalibration(100, 50, 800, 600, 1000, 800)

    @Test fun `a frame box maps to the display by origin plus fraction of the content size`() {
        val d = cal.frameBoxToDisplay(NormalizedBox(0.25f, 0.5f, 0.75f, 0.75f))
        assertEquals((100 + 200) / 1000f, d.left, 1e-5f)
        assertEquals((50 + 300) / 800f, d.top, 1e-5f)
        assertEquals((100 + 600) / 1000f, d.right, 1e-5f)
        assertEquals((50 + 450) / 800f, d.bottom, 1e-5f)
    }

    @Test fun `a fullscreen window at the origin maps frame fractions to the same fractions`() {
        val full = AppWindowCalibration(0, 0, 1000, 800, 1000, 800)
        val b = NormalizedBox(0.1f, 0.2f, 0.9f, 0.3f)
        assertEquals(b, full.frameBoxToDisplay(b))
    }

    @Test fun `it is valid only for the exact sizes it was made for`() {
        assertTrue(cal.isValidFor(800, 600, 1000, 800))
        assertFalse(cal.isValidFor(801, 600, 1000, 800))
        assertFalse(cal.isValidFor(800, 600, 800, 1000)) // rotated display
        assertFalse(cal.isValidFor(0, 0, 0, 0))
    }

    @Test fun `alignment outside the display is flagged`() {
        assertTrue(cal.fitsDisplay)
        assertFalse(AppWindowCalibration(300, 0, 800, 600, 1000, 800).fitsDisplay)
        assertFalse(AppWindowCalibration(-5, 0, 800, 600, 1000, 800).fitsDisplay)
    }

    @Test fun `encode and decode round trip, bad text gives none`() {
        assertEquals(cal, AppWindowCalibration.decode(cal.encode()))
        assertNull(AppWindowCalibration.decode("1,2,3")); assertNull(AppWindowCalibration.decode("a,b,c,d,e,f")); assertNull(AppWindowCalibration.decode(null))
    }
}
