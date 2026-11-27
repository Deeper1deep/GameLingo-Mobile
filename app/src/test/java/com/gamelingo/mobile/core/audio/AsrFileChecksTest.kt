package com.gamelingo.mobile.core.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsrFileChecksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }

    @Test fun `known digests of a small file`() {
        val f = tmp.newFile().apply { writeText("abc") }
        val (md5, sha) = AsrFileChecks.digests(f)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
    }

    @Test fun `matches requires size md5 and sha256`() {
        val f = tmp.newFile().apply { writeText("abc") }
        val spec = AsrModelSpec("x", "u", 3, "900150983cd24fb0d6963f7d28e17f72",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "r", 3, "l", "p")
        assertTrue(AsrFileChecks.matches(f, spec))
        assertFalse(AsrFileChecks.matches(f, spec.copy(sizeBytes = 4)))
        assertFalse(AsrFileChecks.matches(f, spec.copy(md5 = "0".repeat(32))))
        assertFalse(AsrFileChecks.matches(f, spec.copy(sha256 = "0".repeat(64))))
    }

    @Test fun `the pinned small model spec has well formed hashes`() {
        val s = AsrModelSpec.SMALL_EN_US
        assertEquals(32, s.md5.length); assertEquals(64, s.sha256.length)
        assertTrue(s.url.startsWith("https://"))
    }

    @Test fun `unzip extracts normal entries`() {
        val dir = tmp.newFolder()
        AsrFileChecks.unzipSafely(zipOf("m/a.txt" to "hi", "m/sub/b.txt" to "yo").inputStream(), dir, 1_000)
        assertEquals("hi", File(dir, "m/a.txt").readText())
        assertEquals("yo", File(dir, "m/sub/b.txt").readText())
    }

    @Test fun `unzip refuses a path escaping the target`() {
        val dir = tmp.newFolder()
        try { AsrFileChecks.unzipSafely(zipOf("../evil.txt" to "x").inputStream(), dir, 1_000); fail() }
        catch (e: IllegalArgumentException) { /* expected */ }
        assertFalse(File(dir.parentFile, "evil.txt").exists())
    }

    @Test fun `unzip refuses an archive over the size cap`() {
        val dir = tmp.newFolder()
        try { AsrFileChecks.unzipSafely(zipOf("a" to "x".repeat(500)).inputStream(), dir, 100); fail() }
        catch (e: IllegalArgumentException) { /* expected */ }
    }
}
