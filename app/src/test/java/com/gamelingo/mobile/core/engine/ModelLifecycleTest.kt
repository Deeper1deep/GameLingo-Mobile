package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.core.audio.AsrEngine
import com.gamelingo.mobile.core.audio.AsrUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLifecycleTest {
    private class FakeAsr : AsrEngine {
        var closed = 0
        override fun acceptFrame(pcm: ShortArray): AsrUpdate? = null
        override fun finish(): String = ""
        override fun reset() {}
        override fun close() { closed++ }
    }

    @Test fun `a model that finished loading after stop is closed and not adopted`() {
        val asr = FakeAsr(); var adopted: AsrEngine? = null
        assertFalse(ModelLifecycle.adoptOrClose(stillActive = false, engine = asr) { adopted = it })
        assertEquals(1, asr.closed); assertEquals(null, adopted)
    }

    @Test fun `a model loaded while still active is adopted and left open`() {
        val asr = FakeAsr(); var adopted: AsrEngine? = null
        assertTrue(ModelLifecycle.adoptOrClose(true, asr) { adopted = it })
        assertEquals(0, asr.closed); assertEquals(asr, adopted)
    }

    @Test fun `only critical running-memory trim releases the translation models`() {
        assertFalse(ModelLifecycle.shouldReleaseTranslationModels(5))
        assertFalse(ModelLifecycle.shouldReleaseTranslationModels(10))
        assertTrue(ModelLifecycle.shouldReleaseTranslationModels(15))
        assertTrue(ModelLifecycle.shouldReleaseTranslationModels(80))
    }
}
