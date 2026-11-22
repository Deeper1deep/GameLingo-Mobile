package com.gamelingo.mobile.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStyleTest {

    @Test
    fun `there are seven distinct presets`() {
        val all = OverlayPresetId.values().map { OverlayPresets.appearance(it) }
        assertEquals(7, all.size)
        assertEquals(7, all.toSet().size)
    }

    @Test
    fun `every preset is already inside the safe ranges`() {
        OverlayPresetId.values().forEach {
            val a = OverlayPresets.appearance(it)
            assertEquals("preset $it must not be altered by sanitizing", a, a.sanitized())
        }
    }

    @Test
    fun `no preset makes the text invisible against its own background`() {
        OverlayPresetId.values().forEach {
            val a = OverlayPresets.appearance(it)
            assertTrue("preset $it text colour must differ from background", a.textColor != a.backgroundColor)
        }
    }

    @Test
    fun `out of range values are clamped`() {
        val s = OverlayStyle(letterSpacingSp = 99f, lineHeightMultiplier = 0f, outlineWidthDp = 50f, cornerRadiusDp = 500, paddingDp = -3).sanitized()
        assertEquals(OverlayStyle.MAX_SPACING, s.letterSpacingSp)
        assertEquals(OverlayStyle.MIN_LINE_HEIGHT, s.lineHeightMultiplier)
        assertEquals(OverlayStyle.MAX_OUTLINE, s.outlineWidthDp)
        assertEquals(OverlayStyle.MAX_RADIUS, s.cornerRadiusDp)
        assertEquals(OverlayStyle.MIN_PADDING, s.paddingDp)
    }

    @Test
    fun `style survives a json round trip`() {
        val s = OverlayStyle(font = OverlayFont.SERIF, bold = false, outlineWidthDp = 2f, shadowEnabled = true, positionLocked = true)
        assertEquals(s, OverlayStyle.decodeOrDefault(OverlayStyle.encode(s)))
    }

    @Test
    fun `corrupt json falls back to the default style`() {
        assertEquals(OverlayStyle(), OverlayStyle.decodeOrDefault("{not json"))
        assertEquals(OverlayStyle(), OverlayStyle.decodeOrDefault(null))
        assertEquals(OverlayStyle(), OverlayStyle.decodeOrDefault(""))
    }

    @Test
    fun `unknown fields in stored json are ignored`() {
        val s = OverlayStyle.decodeOrDefault("{\"font\":\"MONO\",\"someFutureField\":1}")
        assertEquals(OverlayFont.MONO, s.font)
    }

    @Test
    fun `custom preset round trips and corrupt data is null`() {
        val a = OverlayPresets.appearance(OverlayPresetId.NEON)
        assertEquals(a, OverlayAppearance.decodeOrNull(OverlayAppearance.encode(a)))
        assertNull(OverlayAppearance.decodeOrNull("garbage"))
        assertNotNull(OverlayAppearance.decodeOrNull(OverlayAppearance.encode(a)))
    }
}
