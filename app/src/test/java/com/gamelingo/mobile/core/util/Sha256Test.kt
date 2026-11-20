package com.gamelingo.mobile.core.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `hashes the well known abc vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofString("abc"),
        )
    }

    @Test
    fun `hashes file contents`() {
        val file: File = tempFolder.newFile("sample.bin")
        file.writeText("abc")
        assertEquals(Sha256.ofString("abc"), Sha256.ofFile(file))
    }

    @Test
    fun `matches is case insensitive on the expected hash`() {
        val file = tempFolder.newFile("sample2.bin")
        file.writeText("abc")
        assertTrue(Sha256.matches(file, Sha256.ofString("abc").uppercase()))
        assertFalse(Sha256.matches(file, Sha256.ofString("abcd")))
    }
}
