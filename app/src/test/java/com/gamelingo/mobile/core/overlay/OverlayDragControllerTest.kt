package com.gamelingo.mobile.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDragControllerTest {

    private val sw = 1080
    private val sh = 2400
    private val vw = 600
    private val vh = 200

    private fun controller() = OverlayDragController(slopPx = 24)

    private fun OverlayDragController.move(x: Float, y: Float, locked: Boolean = false) =
        onMove(x, y, vw, vh, sw, sh, locked)

    @Test fun `a tap is not a drag`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        assertNull(c.move(510f, 1008f))
        assertFalse(c.onEnd())
    }

    @Test fun `dragging horizontally follows the finger without a jump at the slop`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        // First move past the slop: anchored there, the window has not jumped.
        val first = c.move(540f, 1000f)!!
        assertEquals(240, first.left)
        assertEquals(800, first.top)
        val next = c.move(640f, 1000f)!!
        assertEquals(340, next.left)
        assertEquals(800, next.top)
        assertTrue(c.onEnd())
    }

    @Test fun `dragging vertically`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        c.move(500f, 1040f)
        val p = c.move(500f, 1240f)!!
        assertEquals(240, p.left)
        assertEquals(1000, p.top)
    }

    @Test fun `dragging diagonally`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        c.move(540f, 1040f)
        val p = c.move(640f, 1140f)!!
        assertEquals(340, p.left)
        assertEquals(900, p.top)
    }

    @Test fun `the box can be dragged to the bottom centre`() {
        val c = controller()
        c.onDown(500f, 800f, 240, 700)
        c.move(500f, 850f)
        val p = c.move(500f, 2300f)!!
        assertTrue(p.top + vh <= sh)
        assertEquals(240, p.left)
    }

    @Test fun `the box never leaves the screen`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        c.move(560f, 1000f)
        val far = c.move(9000f, 9000f)!!
        assertEquals(sw - vw, far.left)
        assertEquals(sh - vh, far.top)
        val back = c.move(-9000f, -9000f)!!
        assertEquals(0, back.left)
        assertEquals(0, back.top)
    }

    @Test fun `a locked position cannot be dragged`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        assertNull(c.move(900f, 1500f, locked = true))
        assertFalse(c.dragging)
        assertFalse(c.onEnd())
    }

    @Test fun `new subtitles may not re-place the window during a drag but may after it`() {
        val c = controller()
        assertTrue(c.allowsExternalReposition())
        c.onDown(500f, 1000f, 240, 800)
        assertTrue(c.allowsExternalReposition()) // still a tap
        c.move(600f, 1000f)
        assertFalse(c.allowsExternalReposition())
        c.onEnd()
        assertTrue(c.allowsExternalReposition())
    }

    @Test fun `a second touch after a drag starts clean`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        c.move(600f, 1000f)
        c.onEnd()
        c.onDown(100f, 100f, 340, 800)
        assertFalse(c.dragging)
        assertNull(c.move(105f, 105f))
    }

    @Test fun `a drag then the saved position is the dropped one`() {
        val c = controller()
        c.onDown(500f, 1000f, 240, 800)
        c.move(540f, 1000f)
        val dropped = c.move(640f, 1200f)!!
        c.onEnd()
        val saved = PlacementResolver.afterDrag(
            TranslationPlacement(), dropped.left, dropped.top, PlacementPoint(240, 2152), sw, sh, vw,
        )
        // Fixed mode, and resolving it again lands exactly where it was dropped.
        val again = PlacementResolver.resolve(saved, sw, sh, vw, vh, null, null, false, 48)
        assertEquals(dropped.left, again.left, 1)
        assertEquals(dropped.top, again.top, 1)
    }

    @Test fun `a drag in a fixed position survives rotation as the same fractions`() {
        val saved = TranslationPlacement(fixedX = 0.5f, fixedY = 0.8f)
        val portrait = PlacementResolver.resolve(saved, 1080, 2400, 600, 200, null, null, false, 48)
        val landscape = PlacementResolver.resolve(saved, 2400, 1080, 600, 200, null, null, false, 48)
        assertEquals((1080 - 600) / 2, portrait.left)
        assertEquals((2400 - 600) / 2, landscape.left)
        assertTrue(landscape.top + 200 <= 1080)
    }
}
