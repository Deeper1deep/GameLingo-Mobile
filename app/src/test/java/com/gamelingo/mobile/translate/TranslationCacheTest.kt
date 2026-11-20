package com.gamelingo.mobile.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun `put then get returns the stored translation and engine`() {
        val cache = TranslationCache()
        cache.put("Hello there", CachedTranslation("أهلاً", "mlkit-ondevice"))
        assertEquals(
            CachedTranslation("أهلاً", "mlkit-ondevice"),
            cache.get("Hello there"),
        )
    }

    @Test
    fun `keys are normalised so whitespace and case still hit`() {
        val cache = TranslationCache()
        cache.put("Hello   there", CachedTranslation("أهلاً", "mlkit-ondevice"))
        assertEquals(
            CachedTranslation("أهلاً", "mlkit-ondevice"),
            cache.get("  hello there "),
        )
    }

    @Test
    fun `miss returns null`() {
        val cache = TranslationCache()
        assertNull(cache.get("never stored"))
    }

    @Test
    fun `put overwrites the previous translation for the same key`() {
        val cache = TranslationCache()
        cache.put("line", CachedTranslation("قديمة", "mlkit-ondevice"))
        cache.put("line", CachedTranslation("جديدة", "gemini-flash-lite"))
        assertEquals("جديدة", cache.get("line")?.translatedText)
        assertEquals("gemini-flash-lite", cache.get("line")?.engineId)
    }

    @Test
    fun `cache never grows beyond the entry cap`() {
        val cache = TranslationCache()
        repeat(250) { index ->
            cache.put("line $index", CachedTranslation("ترجمة $index", "mlkit-ondevice"))
        }
        assertEquals(200, cache.size())
        // The oldest entries were evicted, the newest survive.
        assertNull(cache.get("line 0"))
        assertEquals("ترجمة 249", cache.get("line 249")?.translatedText)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = TranslationCache()
        cache.put("line", CachedTranslation("ترجمة", "mlkit-ondevice"))
        cache.clear()
        assertNull(cache.get("line"))
        assertEquals(0, cache.size())
    }
}
