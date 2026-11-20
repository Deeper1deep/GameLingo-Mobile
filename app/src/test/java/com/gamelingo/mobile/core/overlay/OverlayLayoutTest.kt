package com.gamelingo.mobile.core.overlay

import com.gamelingo.mobile.data.settings.OverlayPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    @Test
    fun `top and bottom positions anchor as expected`() {
        assertTrue(OverlayLayout.anchorsTop(OverlayPosition.TOP))
        assertFalse(OverlayLayout.anchorsTop(OverlayPosition.BOTTOM))
    }

    @Test
    fun `full opacity keeps the color untouched`() {
        val color = 0xAA1A237E
        assertEquals(color, OverlayLayout.backgroundWithOpacity(color, 1f))
    }

    @Test
    fun `opacity scales the existing alpha channel`() {
        val color = 0xAA000000 // alpha 170
        val result = OverlayLayout.backgroundWithOpacity(color, 0.5f)
        val alpha = (result ushr 24) and 0xFF
        assertEquals(85, alpha)
        // RGB is preserved.
        assertEquals(0x1A237E, OverlayLayout.backgroundWithOpacity(0xAA1A237E, 0.5f) and 0xFFFFFF)
    }

    @Test
    fun `zero opacity is fully transparent`() {
        val result = OverlayLayout.backgroundWithOpacity(0xAA1A237E, 0f)
        assertEquals(0L, (result ushr 24) and 0xFF)
    }

    @Test
    fun `out of range opacity is clamped`() {
        val color = 0xAA1A237E
        assertEquals(color, OverlayLayout.backgroundWithOpacity(color, 3f))
    }

    @Test
    fun `drag offset is clamped so the window stays on screen`() {
        assertEquals(0, OverlayLayout.clampVerticalOffset(-50, viewHeight = 100, screenHeight = 800))
        assertEquals(
            700,
            OverlayLayout.clampVerticalOffset(900, viewHeight = 100, screenHeight = 800),
        )
        assertEquals(
            200,
            OverlayLayout.clampVerticalOffset(200, viewHeight = 100, screenHeight = 800),
        )
    }

    @Test
    fun `clamp never goes negative when the view is taller than the screen`() {
        assertEquals(0, OverlayLayout.clampVerticalOffset(50, viewHeight = 900, screenHeight = 800))
    }

    @Test
    fun `horizontal drag is clamped to the free space around the centered window`() {
        // Window 200 wide on an 800 screen: +/- 300 of free space.
        assertEquals(300, OverlayLayout.clampHorizontalOffset(900, viewWidth = 200, screenWidth = 800))
        assertEquals(-300, OverlayLayout.clampHorizontalOffset(-900, viewWidth = 200, screenWidth = 800))
        assertEquals(120, OverlayLayout.clampHorizontalOffset(120, viewWidth = 200, screenWidth = 800))
    }

    @Test
    fun `a window as wide as the screen cannot move horizontally`() {
        assertEquals(0, OverlayLayout.clampHorizontalOffset(100, viewWidth = 800, screenWidth = 800))
        assertEquals(0, OverlayLayout.clampHorizontalOffset(-100, viewWidth = 900, screenWidth = 800))
    }
}
