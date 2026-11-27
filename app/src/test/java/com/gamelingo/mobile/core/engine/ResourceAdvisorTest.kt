package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.core.engine.ResourceAdvisor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the arithmetic. It says nothing about real model memory use. */
class ResourceAdvisorTest {
    private fun a(avail: Long, files: Long? = null, low: Boolean = false, speech: Boolean = false, marian: Long? = null, total: Long = 8000) =
        ResourceAdvisor.advise(avail, total, low, 300, files, marian, speech)

    @Test fun `system low-memory flag is always risky`() {
        assertEquals(Level.RISKY, a(5000, low = true).level)
    }

    @Test fun `verdict ignores model file bytes entirely`() {
        assertEquals(Level.COMFORTABLE, a(4000, files = 100).level)
        assertEquals(Level.COMFORTABLE, a(4000, files = 50_000).level)
        assertEquals(a(4000, files = 100).level, a(4000, files = 50_000).level)
    }

    @Test fun `low available share of total is tight (heuristic)`() {
        assertEquals(Level.TIGHT, a(1000).level)
        assertEquals(Level.COMFORTABLE, a(1300).level)
    }

    @Test fun `file sizes are summed as storage and the app PSS is passed through`() {
        val x = a(1000, files = 2100, marian = 160, speech = true)
        assertEquals(2100 + 160 + 71L, x.modelStorageMb)
        assertEquals(300L, x.appPssMb)
    }
}
