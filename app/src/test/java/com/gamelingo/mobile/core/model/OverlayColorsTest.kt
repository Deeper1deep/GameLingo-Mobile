package com.gamelingo.mobile.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayColorsTest {

    @Test
    fun `default text color is one of the offered presets`() {
        assertTrue(OverlayColors.isTextPreset(OverlayColors.DEFAULT_TEXT))
    }

    @Test
    fun `default background color is one of the offered presets`() {
        assertTrue(OverlayColors.isBackgroundPreset(OverlayColors.DEFAULT_BACKGROUND))
    }

    @Test
    fun `presets have no duplicates`() {
        assertEquals(
            OverlayColors.TEXT_PRESETS.size,
            OverlayColors.TEXT_PRESETS.toSet().size,
        )
        assertEquals(
            OverlayColors.BACKGROUND_PRESETS.size,
            OverlayColors.BACKGROUND_PRESETS.toSet().size,
        )
    }

    @Test
    fun `hex formatting renders alpha red green blue`() {
        assertEquals("#FFFFFFFF", OverlayColors.formatArgbHex(0xFFFFFFFF))
        assertEquals("#AA000000", OverlayColors.formatArgbHex(0xAA000000L))
        assertEquals("#FF00FF00", OverlayColors.formatArgbHex(0xFF00FF00L))
    }

    @Test
    fun `app default settings reference valid overlay colors`() {
        val defaults = com.gamelingo.mobile.data.settings.AppSettings()
        assertTrue(OverlayColors.isTextPreset(defaults.overlayTextColor))
        assertTrue(OverlayColors.isBackgroundPreset(defaults.overlayBackgroundColor))
    }
}
