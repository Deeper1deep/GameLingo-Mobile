package com.gamelingo.mobile.core.update

import com.gamelingo.mobile.core.util.Sha256
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamCopierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copies every byte and reports progress`() {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val dest = tmp.newFile("app.apk")
        val progress = mutableListOf<Long>()
        val written = StreamCopier.copy(ByteArrayInputStream(payload), dest) { progress += it }
        assertEquals(payload.size.toLong(), written)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
        // The copied file hashes exactly like the payload: the SHA-256 gate downstream
        // can trust what it reads from disk.
        assertEquals(Sha256.ofBytes(payload), Sha256.ofFile(dest))
    }

    @Test
    fun `a failing stream leaves no partial file behind`() {
        val broken = object : InputStream() {
            private var emitted = 0
            override fun read(): Int = if (emitted++ < 10) 65 else throw java.io.IOException("boom")
        }
        val dest = File(tmp.root, "partial.apk")
        try {
            StreamCopier.copy(broken, dest)
            fail("expected the copy to throw")
        } catch (_: java.io.IOException) {
        }
        assertFalse(dest.exists())
    }

    @Test
    fun `sha256 match accepts either hex case`() {
        val file = tmp.newFile("ok.apk")
        file.writeBytes("gamelingo".toByteArray())
        val hex = Sha256.ofFile(file)
        assertTrue(Sha256.matches(file, hex.uppercase()))
        assertTrue(Sha256.matches(file, hex.lowercase()))
        assertFalse(Sha256.matches(file, hex.reversed()))
    }
}
