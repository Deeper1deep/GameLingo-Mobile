package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignGeometryTest {
    @Test fun `a window that fits is ok, including a tiny one and a fullscreen one`() {
        assertEquals(AlignGeometry.Check.Ok, AlignGeometry.check(400, 300, 1080, 2400))
        assertEquals(AlignGeometry.Check.Ok, AlignGeometry.check(60, 40, 1080, 2400))
        assertEquals(AlignGeometry.Check.Ok, AlignGeometry.check(1080, 2400, 1080, 2400))
    }

    @Test fun `oversized content is rejected, not shrunk`() {
        assertEquals(AlignGeometry.Check.LargerThanDisplay, AlignGeometry.check(1100, 2400, 1080, 2400))
        assertEquals(AlignGeometry.Check.LargerThanDisplay, AlignGeometry.check(1080, 2500, 1080, 2400))
    }

    @Test fun `zero or negative sizes are invalid`() {
        assertEquals(AlignGeometry.Check.Invalid, AlignGeometry.check(0, 300, 1080, 2400))
        assertEquals(AlignGeometry.Check.Invalid, AlignGeometry.check(400, -1, 1080, 2400))
    }
}
