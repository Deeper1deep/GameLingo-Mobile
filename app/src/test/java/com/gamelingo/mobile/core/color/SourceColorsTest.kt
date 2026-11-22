package com.gamelingo.mobile.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceColorsTest {

    private val W = 20
    private val H = 10

    private fun frame(bg: Int, fg: Int, outline: Int? = null): IntArray {
        val px = IntArray(W * H) { bg }
        for (y in 3..6) for (x in 5..14) px[y * W + x] = fg
        if (outline != null) {
            for (x in 4..15) { px[2 * W + x] = outline; px[7 * W + x] = outline }
        }
        return px
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `red text on a dark background is detected as red`() {
        val c = SourceColorDetector.dominantTextColor(frame(rgb(20, 20, 20), rgb(230, 30, 30)), W, H)
        assertNotNull(c)
        assertTrue((c!! shr 16 and 0xFF) > 200)
        assertTrue(SourceColorDetector.isChromatic(c))
    }

    @Test
    fun `a coloured background is not mistaken for coloured text`() {
        val c = SourceColorDetector.dominantTextColor(frame(rgb(30, 60, 200), rgb(255, 255, 255)), W, H)
        assertNotNull(c)
        assertFalse(SourceColorDetector.isChromatic(c!!))
    }

    @Test
    fun `flat area has no confident colour`() =
        assertNull(SourceColorDetector.dominantTextColor(IntArray(W * H) { rgb(40, 40, 40) }, W, H))

    @Test
    fun `too few foreground pixels is not confident`() {
        val px = IntArray(W * H) { rgb(10, 10, 10) }
        px[5 * W + 8] = rgb(255, 0, 0)
        assertNull(SourceColorDetector.dominantTextColor(px, W, H))
    }

    @Test
    fun `a thin dark outline does not win over the fill`() {
        val c = SourceColorDetector.dominantTextColor(
            frame(rgb(120, 120, 120), rgb(250, 220, 40), outline = rgb(0, 0, 0)), W, H,
        )
        assertNotNull(c)
        assertTrue(SourceColorDetector.isChromatic(c!!))
    }

    @Test
    fun `malformed input is rejected`() {
        assertNull(SourceColorDetector.dominantTextColor(IntArray(3), 20, 10))
        assertNull(SourceColorDetector.dominantTextColor(IntArray(1), 1, 1))
    }

    @Test
    fun `white grey and black are not chromatic`() {
        assertFalse(SourceColorDetector.isChromatic(rgb(255, 255, 255)))
        assertFalse(SourceColorDetector.isChromatic(rgb(128, 128, 128)))
        assertFalse(SourceColorDetector.isChromatic(rgb(10, 10, 10)))
        assertTrue(SourceColorDetector.isChromatic(rgb(255, 200, 0)))
    }

    private val red = rgb(230, 30, 30)
    private val gold = rgb(250, 200, 40)

    @Test
    fun `original line spans follow word positions`() {
        val spans = ColorAligner.forOriginal("Get the Medkit now", listOf(SourceWordColor("Medkit", gold)))
        assertEquals(listOf(ColorSpan(8, 14, gold)), spans)
    }

    @Test
    fun `a repeated word consumes one detection per occurrence`() {
        val spans = ColorAligner.forOriginal("run run", listOf(SourceWordColor("run", red)))
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
    }

    @Test
    fun `translation is coloured only through a certain glossary match`() {
        val spans = ColorAligner.forTranslation(
            original = "Take the medkit",
            translated = "خد حقيبة إسعافات",
            colored = listOf(SourceWordColor("medkit", gold)),
            lookup = { if (it == "medkit") "حقيبة إسعافات" else null },
        )
        assertEquals(1, spans.size)
        assertEquals("حقيبة إسعافات", "خد حقيبة إسعافات".substring(spans[0].start, spans[0].end))
    }

    @Test
    fun `an unknown coloured word is never guessed onto the translation`() {
        val spans = ColorAligner.forTranslation(
            original = "Take the medkit",
            translated = "خد حقيبة إسعافات",
            colored = listOf(SourceWordColor("medkit", gold)),
            lookup = { null },
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `a latin name kept in the translation is coloured`() {
        val spans = ColorAligner.forTranslation(
            original = "Ellie wait!",
            translated = "Ellie استني!",
            colored = listOf(SourceWordColor("Ellie", red)),
            lookup = { null },
        )
        assertEquals(listOf(ColorSpan(0, 5, red)), spans)
    }
}
