package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.core.engine.ResourceAdvisor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the arithmetic of the estimate. It says nothing about real device memory. */
class ResourceAdvisorTest {
    @Test fun `nothing loaded is comfortable`() {
        assertEquals(Level.COMFORTABLE, ResourceAdvisor.advise(6000, null, null, false).level)
    }

    @Test fun `gemma plus speech on a 3 GB device is risky, on 4 GB only tight`() {
        val a = ResourceAdvisor.advise(3000, 2100, 160, true)
        assertEquals(2100 + 160 + 71L, a.estimatedModelMb)
        assertEquals(Level.RISKY, a.level)
        assertEquals(Level.TIGHT, ResourceAdvisor.advise(4000, 2100, 160, true).level)
    }

    @Test fun `thresholds are exclusive boundaries`() {
        assertEquals(Level.COMFORTABLE, ResourceAdvisor.advise(1000, 400, null, false).level)
        assertEquals(Level.TIGHT, ResourceAdvisor.advise(1000, 401, null, false).level)
        assertEquals(Level.TIGHT, ResourceAdvisor.advise(1000, 650, null, false).level)
        assertEquals(Level.RISKY, ResourceAdvisor.advise(1000, 651, null, false).level)
    }

    @Test fun `unknown total ram is never alarming`() {
        assertEquals(Level.COMFORTABLE, ResourceAdvisor.advise(0, 5000, 5000, true).level)
    }
}
