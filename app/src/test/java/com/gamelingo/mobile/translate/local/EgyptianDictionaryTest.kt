package com.gamelingo.mobile.translate.local

import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Pins the Egyptian dictionary rules that decide whether output reads as real
 * spoken Egyptian or as MSA: whole-line overrides, profanity that fires only
 * when the source is profane, and Arabic word boundaries that treat the Arabic
 * comma and question mark as separators (they are punctuation, not letters).
 */
class EgyptianDictionaryTest {

    @Test
    fun `whole-line override fires on punctuation-insensitive match`() {
        assertEquals("يا نهار ابيض!", dictionary.toEgyptian("holy shit!", "يا إلهي!"))
        // Case-insensitive but punctuation-exact: the no-exclamation variant is its own override.
        assertEquals("يا نهار ابيض", dictionary.toEgyptian("Holy shit", "يا إلهي!"))
    }

    @Test
    fun `override does not fire for a different line`() {
        val out = dictionary.toEgyptian("holy shit that was close", "الشرطة هنا")
        assertFalse(out == "يا نهار ابيض!")
    }

    @Test
    fun `msa map swaps police for the egyptian word`() {
        assertEquals("البوليس وراك", dictionary.toEgyptian("the cops are after you", "الشرطة وراك"))
    }

    @Test
    fun `arabic comma is a boundary not a letter`() {
        // "الشرطة" followed directly by an Arabic comma must still be replaced.
        assertEquals("البوليس، بسرعة", dictionary.toEgyptian("cops, quick", "الشرطة، بسرعة"))
    }

    @Test
    fun `no replacement inside a longer arabic word`() {
        // "المال" is a prefix of "المالكي"; the boundary rule must leave it alone.
        assertEquals("المالكي قال كده", dictionary.toEgyptian("al-maliki said so", "المالكي قال كده"))
    }

    @Test
    fun `profanity upgrades only when the source is profane`() {
        val profane = dictionary.toEgyptian("fuck you", "تباً لك")
        assertTrue(profanityShouldUpgrade(profane))
        // Same MSA output but a clean source: the softened word stays untouched.
        val clean = dictionary.toEgyptian("oh no man", "تباً لك")
        assertEquals("تباً لك", clean)
    }

    @Test
    fun `profanity marker respects english word boundaries`() {
        assertFalse(dictionary.sourceHasProfanity("shutter speed"))
        assertTrue(dictionary.sourceHasProfanity("shit"))
    }

    @Test
    fun `glossary terms surface dictionary words present in the source`() {
        val terms = dictionary.glossaryTerms("the cops found my ride")
        assertEquals("البوليس", terms["cops"])
        assertFalse(terms.containsKey("helicopter"))
    }

    @Test
    fun `unknown override returns null`() {
        assertNull(dictionary.overrideFor("a line nobody wrote"))
    }

    private fun profanityShouldUpgrade(text: String): Boolean = text != "تباً لك" && !text.contains("تباً")

    companion object {
        private lateinit var dictionary: EgyptianDictionary

        @JvmStatic
        @BeforeClass
        fun loadDictionary() {
            val stream = EgyptianDictionaryTest::class.java.classLoader
                ?.getResourceAsStream("egyptian_dictionary.json")
                ?: error("test resource missing: egyptian_dictionary.json")
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            dictionary = EgyptianDictionary.load(text)
        }
    }
}
