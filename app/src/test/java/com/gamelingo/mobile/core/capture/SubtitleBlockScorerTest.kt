package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame geometry mirrors the on-device case: 2400x1080 landscape.
 * The watermark rect mirrors the "GAMEPLAY ONLY" corner box from the user's
 * v0.1.2 screenshot; the subtitle rect mirrors the bottom-center dialogue line.
 */
class SubtitleBlockScorerTest {

    private val frameWidth = 2400
    private val frameHeight = 1080

    private val watermark = RecognizedBlock(
        "GAMEPLAY ONLY",
        PixelRect(2260, 980, 2380, 1040),
    )

    private fun subtitle(text: String) =
        RecognizedBlock(text, PixelRect(880, 990, 1520, 1045))

    @Test
    fun `changing bottom center line beats static corner watermark`() {
        val dynamics = BlockDynamicsTracker()
        // Watermark sits identical for many frames; the subtitle keeps changing.
        repeat(10) { i ->
            val line = subtitle("Subtitle line number $i of the dialogue")
            dynamics.offer(listOf(watermark, line))
        }
        val best = SubtitleBlockScorer.selectBest(
            listOf(watermark, subtitle("Joel: It is nice but I-")),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertTrue(best != null)
        assertTrue(best!!.text.startsWith("Joel:"))
    }

    @Test
    fun `static watermark alone falls below threshold and yields nothing`() {
        val dynamics = BlockDynamicsTracker()
        repeat(10) { dynamics.offer(listOf(watermark)) }
        val best = SubtitleBlockScorer.selectBest(
            listOf(watermark),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertNull(best)
    }

    @Test
    fun `bottom center line is selected from the very first frame`() {
        val dynamics = BlockDynamicsTracker()
        val best = SubtitleBlockScorer.selectBest(
            listOf(subtitle("Joel: It is nice but I-")),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertTrue(best != null)
    }

    @Test
    fun `a paused subtitle line is damped but still shown`() {
        val dynamics = BlockDynamicsTracker()
        val line = subtitle("Joel: It is nice but I-")
        // The same line sits unchanged far beyond the static threshold,
        // like a long held shot on one subtitle.
        repeat(15) { dynamics.offer(listOf(line)) }
        val best = SubtitleBlockScorer.selectBest(
            listOf(line),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertTrue(best != null)
        assertEquals(line, best)
    }

    @Test
    fun `empty frame yields no selection`() {
        val dynamics = BlockDynamicsTracker()
        assertNull(
            SubtitleBlockScorer.selectBest(emptyList(), frameWidth, frameHeight, dynamics),
        )
    }

    @Test
    fun `two stacked subtitle lines are merged top to bottom`() {
        val dynamics = BlockDynamicsTracker()
        val upper = RecognizedBlock("Joel: It is nice but I-", PixelRect(900, 920, 1500, 975))
        val lower = RecognizedBlock("don't want to talk about it", PixelRect(880, 990, 1520, 1045))
        val merged = SubtitleBlockScorer.selectSubtitle(
            listOf(upper, lower),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertEquals("Joel: It is nice but I- don't want to talk about it", merged)
    }

    @Test
    fun `corner watermark is never merged into the subtitle`() {
        val dynamics = BlockDynamicsTracker()
        val merged = SubtitleBlockScorer.selectSubtitle(
            listOf(watermark, subtitle("Joel: It is nice but I-")),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertEquals("Joel: It is nice but I-", merged)
    }

    @Test
    fun `unrelated text far above the winner is not merged`() {
        val dynamics = BlockDynamicsTracker()
        // Centered but far from the winner: a HUD label, not a wrapped line.
        val hud = RecognizedBlock("MISSION 3 DAYS GONE", PixelRect(900, 700, 1500, 755))
        val merged = SubtitleBlockScorer.selectSubtitle(
            listOf(hud, subtitle("Joel: It is nice but I-")),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertEquals("Joel: It is nice but I-", merged)
    }

    @Test
    fun `select subtitle returns null when only a static watermark is visible`() {
        val dynamics = BlockDynamicsTracker()
        repeat(10) { dynamics.offer(listOf(watermark)) }
        assertNull(
            SubtitleBlockScorer.selectSubtitle(
                listOf(watermark),
                frameWidth,
                frameHeight,
                dynamics,
            ),
        )
    }

    @Test
    fun `corner watermark scores far below bottom center subtitle`() {
        val dynamics = BlockDynamicsTracker()
        val watermarkScore = SubtitleBlockScorer.score(watermark, frameWidth, frameHeight, dynamics)
        val subtitleScore = SubtitleBlockScorer.score(
            subtitle("Joel: It is nice but I-"),
            frameWidth,
            frameHeight,
            dynamics,
        )
        assertTrue(subtitleScore > watermarkScore * 4)
    }
}
