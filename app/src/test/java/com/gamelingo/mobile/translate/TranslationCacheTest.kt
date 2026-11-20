package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    private val localContext = CacheContext(
        aiMode = AiMode.LOCAL,
        plannedEngineId = "mlkit-ondevice",
        promptVersion = EgyptianDialectPrompt.PROMPT_VERSION,
        contextSignature = 0,
    )

    private val autoContext = CacheContext(
        aiMode = AiMode.AUTO,
        plannedEngineId = "gemini-flash-lite",
        promptVersion = EgyptianDialectPrompt.PROMPT_VERSION,
        contextSignature = 0,
    )

    @Test
    fun `put then get returns the stored translation and engine`() {
        val cache = TranslationCache()
        cache.put("Hello there", localContext, CachedTranslation("أهلاً", "mlkit-ondevice"))
        assertEquals(
            CachedTranslation("أهلاً", "mlkit-ondevice"),
            cache.get("Hello there", localContext),
        )
    }

    @Test
    fun `keys are normalised so whitespace and case still hit`() {
        val cache = TranslationCache()
        cache.put("Hello   there", localContext, CachedTranslation("أهلاً", "mlkit-ondevice"))
        assertEquals(
            CachedTranslation("أهلاً", "mlkit-ondevice"),
            cache.get("  hello there ", localContext),
        )
    }

    @Test
    fun `miss returns null`() {
        val cache = TranslationCache()
        assertNull(cache.get("never stored", localContext))
    }

    @Test
    fun `same text with a different context signature misses`() {
        val cache = TranslationCache()
        cache.put("line", localContext, CachedTranslation("ترجمة", "mlkit-ondevice"))
        val withDialogue = localContext.copy(
            contextSignature = CacheContext.signatureOf(listOf("earlier line")),
        )
        assertNull(cache.get("line", withDialogue))
    }

    @Test
    fun `same text with a different ai mode misses`() {
        val cache = TranslationCache()
        cache.put("line", localContext, CachedTranslation("ترجمة", "mlkit-ondevice"))
        assertNull(cache.get("line", autoContext))
    }

    @Test
    fun `LOCAL mode never serves a cached online result`() {
        val cache = TranslationCache()
        // An entry sneaks in under the local-looking context but was produced online.
        cache.put("line", localContext, CachedTranslation("ترجمة أونلاين", "gemini-flash-lite"))
        assertNull(cache.get("line", localContext))
    }

    @Test
    fun `AUTO mode accepts an entry from either engine`() {
        val cache = TranslationCache()
        cache.put("line", autoContext, CachedTranslation("ترجمة", "mlkit-ondevice"))
        assertEquals("ترجمة", cache.get("line", autoContext)?.translatedText)
    }

    @Test
    fun `a prompt version bump invalidates old entries`() {
        val cache = TranslationCache()
        cache.put("line", autoContext, CachedTranslation("قديمة", "gemini-flash-lite"))
        val newPrompt = autoContext.copy(promptVersion = autoContext.promptVersion + 1)
        assertNull(cache.get("line", newPrompt))
    }

    @Test
    fun `put overwrites the previous translation for the same key`() {
        val cache = TranslationCache()
        cache.put("line", autoContext, CachedTranslation("قديمة", "mlkit-ondevice"))
        cache.put("line", autoContext, CachedTranslation("جديدة", "gemini-flash-lite"))
        assertEquals("جديدة", cache.get("line", autoContext)?.translatedText)
        assertEquals("gemini-flash-lite", cache.get("line", autoContext)?.engineId)
    }

    @Test
    fun `cache never grows beyond the entry cap`() {
        val cache = TranslationCache()
        repeat(250) { index ->
            cache.put("line $index", localContext, CachedTranslation("ترجمة $index", "mlkit-ondevice"))
        }
        assertEquals(200, cache.size())
        // The oldest entries were evicted, the newest survive.
        assertNull(cache.get("line 0", localContext))
        assertEquals("ترجمة 249", cache.get("line 249", localContext)?.translatedText)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = TranslationCache()
        cache.put("line", localContext, CachedTranslation("ترجمة", "mlkit-ondevice"))
        cache.clear()
        assertNull(cache.get("line", localContext))
        assertEquals(0, cache.size())
    }
}
