package com.gamelingo.mobile.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PipelineTraceTest {
    private fun ev(i: Long) = PipelineTrace.Event(i, i, "accepted", "e", 10, 12, listOf("X"), false, false, 5)

    @Test fun `the trace is bounded`() {
        PipelineTrace.clear()
        for (i in 1..(PipelineTrace.CAPACITY + 50L)) PipelineTrace.record(ev(i))
        assertEquals(PipelineTrace.CAPACITY, PipelineTrace.snapshot().size)
        assertEquals(51L, PipelineTrace.snapshot().first().subtitleId)
        PipelineTrace.clear()
    }

    @Test fun `export carries lengths and flags but no text fields`() {
        PipelineTrace.clear()
        PipelineTrace.record(ev(1))
        val out = PipelineTrace.export()
        assertFalse(out.contains("sourceText"))
        assertEquals(true, out.contains("src=10c out=12c"))
        PipelineTrace.clear()
    }

    @Test fun `ocr stage is bounded, metadata only and reports missing confidence as unavailable`() {
        PipelineTrace.clear()
        fun o(i: Long, c: Float?) = PipelineTrace.OcrEvent(i, 1080, 2340, 1080, 300, "0,2000,1080,2300", 2, c, 40, 38, "OFF", 12)
        for (i in 1..(PipelineTrace.OCR_CAPACITY + 10L)) PipelineTrace.recordOcr(o(i, null))
        assertEquals(PipelineTrace.OCR_CAPACITY, PipelineTrace.ocrSnapshot().size)
        PipelineTrace.recordOcr(o(999, 0.87f))
        val out = PipelineTrace.export()
        assertEquals(true, out.contains("conf=unavailable"))
        assertEquals(true, out.contains("conf=0.87"))
        assertEquals(true, out.contains("frame=1080x2340 crop=1080x300 region=0,2000,1080,2300 blocks=2"))
        assertEquals(true, out.contains("not recorded: token ids"))
        PipelineTrace.clear()
    }
}
