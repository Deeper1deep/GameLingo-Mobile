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

    @Test fun `a failed resize never creates a second display and leaves the old pair untouched`() {
        val dead = FakeDisplay(failResize = true)
        val old = FakeReader("old")
        val surface = CaptureSurface<FakeReader, FakeDisplay>(display = dead, reader = old)
        val b = FakeReader("new")
        try {
            CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory())
            fail("expected a swap failure")
        } catch (expected: CaptureSwapException) {
        }
        assertEquals(0, displaysCreated) // createVirtualDisplay is single-use per projection
        assertTrue(b.closed)
        assertFalse(old.closed) // may still be attached: never closed on failure
        assertFalse(dead.released)
        assertSame(dead, surface.display)
        assertSame(old, surface.reader)
    }

    @Test fun `a failed setSurface closes only the new reader and keeps the old pair`() {
        val disp = FakeDisplay(failSetSurface = true)
        val old = FakeReader("old")
        val surface = CaptureSurface<FakeReader, FakeDisplay>(display = disp, reader = old)
        val b = FakeReader("new")
        try {
            CaptureSurfaceSwap.swap(surface, 2400, 1080, 420, b, factory())
            fail("expected a swap failure")
        } catch (expected: CaptureSwapException) {
        }
        assertEquals(0, displaysCreated)
        assertTrue(b.closed)
        assertFalse(old.closed)
        assertSame(old, surface.reader)
    }

    @Test fun `first build failure closes the new reader and leaves the surface empty`() {
        val surface = CaptureSurface<FakeReader, FakeDisplay>()
        val r = FakeReader("a")
        try {
            CaptureSurfaceSwap.swap(surface, 1080, 2400, 420, r, factory(failCreate = true))
            fail("expected failure")
        } catch (expected: IllegalStateException) {
        }
        assertTrue(r.closed)
        assertNull(surface.display)
        assertNull(surface.reader)
    }
}
