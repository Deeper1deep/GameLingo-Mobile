package com.gamelingo.mobile.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuditFixesTranslateTest {

    @Test
    fun `baseEngineId strips pipeline tags`() {
        assertEquals("local-ai-v1", baseEngineId("local-ai-v1+dict+qc"))
        assertEquals("mlkit-ondevice", baseEngineId("mlkit-ondevice"))
    }

    @Test
    fun `glossary signature is order independent and zero when empty`() {
        val a = CacheContext.glossarySignatureOf(listOf("gg" to "جي جي", "nerf" to "تضعيف"))
        val b = CacheContext.glossarySignatureOf(listOf("nerf" to "تضعيف", "gg" to "جي جي"))
        assertEquals(a, b)
        assertEquals(0L, CacheContext.glossarySignatureOf(emptyList()))
    }

    @Test
    fun `glossary signature changes when a term changes`() {
        val a = CacheContext.glossarySignatureOf(listOf("gg" to "جي جي"))
        val b = CacheContext.glossarySignatureOf(listOf("gg" to "حلو"))
        assertNotEquals(a, b)
    }
}
