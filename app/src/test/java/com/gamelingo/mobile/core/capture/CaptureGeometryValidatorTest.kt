package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGeometryValidatorTest {

    private fun proves(cw: Int, ch: Int, frameW: Int = 1080, frameH: Int = 2400) =
        CaptureGeometryValidator.provesFullDisplay(1080, 2400, frameW, frameH, cw, ch)

    @Test fun `no reported content is the display we built the frame from`() {
        assertTrue(proves(0, 0))
    }

    @Test fun `reported content equal to the display is full display capture`() {
        assertTrue(proves(1080, 2400))
        assertTrue(proves(1078, 2398)) // 1% tolerance
    }

    @Test fun `rotation reports the rotated display`() {
        assertTrue(CaptureGeometryValidator.provesFullDisplay(2400, 1080, 2400, 1080, 2400, 1080))
    }

    @Test fun `a single window of a different aspect is not full display`() {
        assertFalse(proves(1080, 1700))
        assertFalse(proves(900, 2400))
    }

    @Test fun `a single window with the same aspect but another size is NOT accepted as full display`() {
        // 720x1600 has the display's aspect (0.45) but is neither the display size nor the frame size.
        assertFalse(proves(720, 1600))
        assertFalse(proves(540, 1200))
    }

    @Test fun `content scaled to the frame size is accepted`() {
        assertTrue(proves(540, 1200, frameW = 540, frameH = 1200))
    }

    @Test fun `invalid dimensions never prove anything`() {
        assertFalse(CaptureGeometryValidator.provesFullDisplay(0, 2400, 1080, 2400, 0, 0))
        assertFalse(CaptureGeometryValidator.provesFullDisplay(1080, 2400, 0, 0, 1080, 2400))
    }
}
