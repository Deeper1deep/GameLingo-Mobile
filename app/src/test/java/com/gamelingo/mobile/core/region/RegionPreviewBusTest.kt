package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import com.gamelingo.mobile.core.capture.RecognizedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPreviewBusTest {

    private val candidate = RegionPreviewBus.Candidate(
        NormalizedRegion(0.1f, 0.65f, 0.9f, 0.95f), RegionSensitivity.BALANCED,
    )

    @Test fun `preview marks accepted and rejected text in screen fractions`() {
        val blocks = listOf(
            RecognizedBlock("subtitle", PixelRect(200, 1800, 900, 1860)),
            RecognizedBlock("hud", PixelRect(20, 50, 300, 90)),
        )
        val result = RegionPreviewBus.build(blocks, 1080, 2400, candidate)
        assertEquals(2, result.boxes.size)
        assertTrue(result.boxes[0].accepted)
        assertFalse(result.boxes[1].accepted)
        assertEquals(200f / 1080, result.boxes[0].left, 0.0001f)
        assertEquals(1860f / 2400, result.boxes[0].bottom, 0.0001f)
    }

    @Test fun `results are only kept while a selector is open`() {
        RegionPreviewBus.setCandidate(null)
        RegionPreviewBus.publish(RegionPreviewBus.Result(emptyList()))
        assertNull(RegionPreviewBus.result.value)
        RegionPreviewBus.setCandidate(candidate)
        RegionPreviewBus.publish(RegionPreviewBus.Result(emptyList()))
        assertEquals(RegionPreviewBus.Result(emptyList()), RegionPreviewBus.result.value)
        RegionPreviewBus.setCandidate(null)
        assertNull(RegionPreviewBus.result.value)
    }
}
