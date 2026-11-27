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
}
