package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.overlay.PlacementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWindowCaptureTest {
    @Test fun `frame keeps the content aspect and never exceeds the reference long edge`() {
        assertEquals(2400 to 1080, AppWindowCapture.frameFor(2400, 1080, 2400))
        assertEquals(1200 to 540, AppWindowCapture.frameFor(2400, 1080, 1200))
        assertEquals(540 to 1200, AppWindowCapture.frameFor(1080, 2400, 1200))
        assertEquals(800 to 600, AppWindowCapture.frameFor(800, 600, 2400))
    }

    @Test fun `invalid sizes give no frame`() {
        assertNull(AppWindowCapture.frameFor(0, 100, 100)); assertNull(AppWindowCapture.frameFor(100, 100, 0))
    }

    @Test fun `startup waits, becomes ready on the first size, and times out without one`() {
        assertEquals(AppWindowCapture.Startup.Waiting, AppWindowCapture.startup(null, 100))
        assertEquals(AppWindowCapture.Startup.Ready(1000, 500), AppWindowCapture.startup(1000 to 500, 100))
        assertEquals(AppWindowCapture.Startup.TimedOut, AppWindowCapture.startup(null, AppWindowCapture.STARTUP_TIMEOUT_MILLIS))
        assertEquals(AppWindowCapture.Startup.Waiting, AppWindowCapture.startup(0 to 0, 10))
    }

    @Test fun `follow below and smart degrade to fixed only in app window capture, with the notice flag`() {
        for (m in listOf(PlacementMode.FOLLOW_ORIGINAL, PlacementMode.BELOW_ORIGINAL, PlacementMode.SMART_AUTO)) {
            val e = AppWindowCapture.effectivePlacement(m, appWindowCapture = true)
            assertEquals(PlacementMode.FIXED, e.mode); assertTrue(e.degraded)
            val whole = AppWindowCapture.effectivePlacement(m, appWindowCapture = false)
            assertEquals(m, whole.mode); assertEquals(false, whole.degraded)
        }
        assertEquals(false, AppWindowCapture.effectivePlacement(PlacementMode.FIXED, true).degraded)
        val c = AppWindowCapture.effectivePlacement(PlacementMode.SMART_AUTO, appWindowCapture = true, calibrated = true)
        assertEquals(PlacementMode.SMART_AUTO, c.mode); assertEquals(false, c.degraded)
    }
}
