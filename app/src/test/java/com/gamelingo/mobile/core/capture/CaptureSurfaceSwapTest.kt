package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The resize transaction: order, rollback, cleanup, and never a second display. */
class CaptureSurfaceSwapTest {

    private val events = mutableListOf<String>()
    private var displaysCreated = 0

    private inner class FakeReader(val name: String) : SurfaceReader {
        var closed = false
        override fun close() { closed = true; events += "close:$name" }
    }

    private inner class FakeDisplay(
        val failResize: Boolean = false,
        val failSetSurface: Boolean = false,
    ) : SurfaceDisplay<FakeReader> {
        var released = false
        var size: Pair<Int, Int>? = null
        var surfaceOf: FakeReader? = null
        override fun resize(width: Int, height: Int, densityDpi: Int) {
            if (failResize) throw IllegalStateException("resize failed")
            size = width to height; events += "resize:${width}x$height"
        }
        override fun setSurface(reader: FakeReader) {
            if (failSetSurface) throw IllegalStateException("setSurface failed")
            surfaceOf = reader; events += "setSurface:${reader.name}"
        }
        override fun release() { released = true; events += "release" }
    }

    private fun factory(failCreate: Boolean = false): (FakeReader) -> FakeDisplay = { r ->
        if (failCreate) throw IllegalStateException("create failed")
        displaysCreated++
        FakeDisplay().also { it.surfaceOf = r; events += "create:${r.name}" }
    }

    @Test fun `first build creates exactly one display`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>()
        val r = FakeReader("a")
        val out = CaptureSurfaceSwap.swap(surface, 1080, 2400, 420, r, factory())
        assertEquals(CaptureSurfaceSwap.Outcome.CREATED, out)
        assertEquals(1, displaysCreated)
        assertSame(r, surface.reader)
    }

    @Test fun `rotation resizes the same display and closes the old reader only after the swap`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>()
        CaptureSurfaceSwap.swap(surface, 1080, 2400, 420, FakeReader("a"), factory())
        events.clear()
        val b = FakeReader("b")
        val display = surface.display!!
        val out = CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory())
        assertEquals(CaptureSurfaceSwap.Outcome.REUSED, out)
        assertEquals(1, displaysCreated) // no duplicate VirtualDisplay
        assertSame(display, surface.display)
        assertEquals(listOf("resize:2400x1080", "setSurface:b", "close:a"), events)
        assertSame(b, surface.reader)
    }

    @Test fun `repeated and duplicate resizes never stack displays and always leave one open reader`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>()
        val readers = (0 until 6).map { FakeReader("r$it") }
        for ((i, r) in readers.withIndex()) {
            val (w, h) = if (i % 2 == 0) 1080 to 2400 else 2400 to 1080
            CaptureSurfaceSwap.swap(surface, w, h, 420, r, factory())
        }
        assertEquals(1, displaysCreated)
        assertEquals(readers.dropLast(1).map { true }, readers.dropLast(1).map { it.closed })
        assertFalse(readers.last().closed)
        assertSame(readers.last(), surface.reader)
    }

    @Test fun `a failed resize releases the dead display and recreates on the new reader`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>(display = FakeDisplay(failResize = true), reader = FakeReader("old"))
        val old = surface.reader!!
        val dead = surface.display!!
        val b = FakeReader("new")
        val out = CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory())
        assertEquals(CaptureSurfaceSwap.Outcome.RECREATED, out)
        assertTrue(dead.released)
        assertTrue(old.closed)
        assertFalse(b.closed)
        assertEquals(1, displaysCreated)
        assertSame(b, surface.reader)
        assertTrue(surface.display !== dead)
    }

    @Test fun `a failed surface swap keeps the old reader open until recreate and never leaks the new one`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>(display = FakeDisplay(failSetSurface = true), reader = FakeReader("old"))
        val b = FakeReader("new")
        events.clear()
        CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory())
        // The old reader is closed only after the failed display was released, never before the swap attempt.
        assertTrue(events.indexOf("release") < events.indexOf("close:old"))
        assertFalse(b.closed)
        assertSame(b, surface.reader)
    }

    @Test fun `when recreate also fails the new reader is closed and no half state remains`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>(display = FakeDisplay(failResize = true), reader = FakeReader("old"))
        val b = FakeReader("new")
        try {
            CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory(failCreate = true))
            fail("expected the create failure to propagate")
        } catch (expected: IllegalStateException) {
            // propagated to the rebuild retry budget
        }
        assertTrue(b.closed)
        assertNull(surface.display)
        assertNull(surface.reader)
        assertEquals(0, displaysCreated)
    }

    @Test fun `first build failure closes the new reader`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>()
        val r = FakeReader("a")
        try {
            CaptureSurfaceSwap.swap(surface, 1080, 2400, 420, r, factory(failCreate = true))
            fail("expected failure")
        } catch (expected: IllegalStateException) {
        }
        assertTrue(r.closed)
        assertNull(surface.display)
    }
}
