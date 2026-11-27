package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.core.engine.ResourceAdvisor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the arithmetic. It says nothing about real model memory use. */
class ResourceAdvisorTest {
    private fun a(avail: Long, files: Long? = null, low: Boolean = false, speech: Boolean = false, marian: Long? = null) =
        ResourceAdvisor.advise(avail, 8000, low, 300, files, marian, speech)

    @Test fun `nothing enabled is comfortable however little RAM is free`() {
        assertEquals(Level.COMFORTABLE, a(100).level)
    }

    @Test fun `system low-memory flag is always risky`() {
        assertEquals(Level.RISKY, a(5000, files = 100, low = true).level)
    }

    @Test fun `less available RAM than the model files is risky, under twice is tight`() {
        assertEquals(Level.RISKY, a(2000, files = 2100).level)
        assertEquals(Level.TIGHT, a(3000, files = 2100).level)
        assertEquals(Level.COMFORTABLE, a(4200, files = 2100).level)
    }

    @Test fun `speech adds the unpacked model size and sums are reported`() {
        val x = a(1000, files = 2100, marian = 160, speech = true)
        assertEquals(2100 + 160 + 71L, x.modelFilesMb)
        assertEquals(300L, x.appPssMb)
    }
}
