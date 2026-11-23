package com.gamelingo.mobile.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgyptianDialectPromptTest {

    @Test
    fun `system prompt demands egyptian colloquial and forbids literal translation`() {
        val prompt = EgyptianDialectPrompt.SYSTEM_PROMPT
        assertTrue(prompt.contains("العامية المصرية"))
        assertTrue(prompt.contains("Egyptian colloquial"))
        assertTrue(prompt.contains("no literal translation"))
        assertTrue(prompt.contains("ONLY"))
    }

    @Test
    fun `system prompt carries the faithful translation mandate verbatim`() {
        val prompt = EgyptianDialectPrompt.SYSTEM_PROMPT
        assertTrue(prompt.contains("Preserve profanity, insults, vulgar language, slang"))
        assertTrue(prompt.contains("original meaning and intensity"))
        assertTrue(prompt.contains("Never censor, omit, sanitize, soften"))
        assertTrue(prompt.contains("asterisks"))
        assertTrue(prompt.contains("Do not invent profanity"))
        assertTrue(prompt.contains("context-aware Egyptian colloquial equivalents"))
    }

    @Test
    fun `prompt version bumped so cached pre-mandate translations are never served`() {
        assertEquals(3, EgyptianDialectPrompt.PROMPT_VERSION)
    }

    @Test
    fun `profanity in the source line passes into the request untouched`() {
        // Our own pipeline must never rewrite the source text: whatever the
        // game says reaches the engine verbatim.
        val line = "You piece of shit, get the hell out of my way, bastard!"
        val out = EgyptianDialectPrompt.buildUserPrompt(TranslationRequest(text = line))
        assertTrue(out.endsWith(line))
    }

    @Test
    fun `slang and sarcasm pass through unmodified`() {
        val line = "Yeah right, tough guy. LMFAO, you wish, dumbass."
        val out = EgyptianDialectPrompt.buildUserPrompt(TranslationRequest(text = line))
        assertTrue(out.endsWith(line))
    }

    @Test
    fun `user prompt always ends with the current line`() {
        val out = EgyptianDialectPrompt.buildUserPrompt(TranslationRequest(text = "Let's go!"))
        assertTrue(out.endsWith("Let's go!"))
        assertTrue(out.contains("Translate this line:"))
    }

    @Test
    fun `context lines are included oldest first`() {
        val out = EgyptianDialectPrompt.buildUserPrompt(
            TranslationRequest(
                text = "current",
                contextLines = listOf("first", "second"),
            ),
        )
        assertTrue(out.contains("- first"))
        assertTrue(out.indexOf("- first") < out.indexOf("- second"))
        assertTrue(out.contains("do not translate"))
    }

    @Test
    fun `empty sections are omitted`() {
        val out = EgyptianDialectPrompt.buildUserPrompt(TranslationRequest(text = "hi"))
        assertFalse(out.contains("Dialogue context"))
        assertFalse(out.contains("Character names:"))
        assertFalse(out.contains("Glossary:"))
    }

    @Test
    fun `names and glossary render when present`() {
        val out = EgyptianDialectPrompt.buildUserPrompt(
            TranslationRequest(
                text = "hi",
                characterNames = listOf("Niko", "Roman"),
                glossary = mapOf("save point" to "نقطة حفظ"),
            ),
        )
        assertTrue(out.contains("Niko, Roman"))
        assertTrue(out.contains("- save point = نقطة حفظ"))
    }

    @Test
    fun `translation request defaults are empty`() {
        val request = TranslationRequest(text = "x")
        assertEquals(emptyList<String>(), request.contextLines)
        assertEquals(emptyList<String>(), request.characterNames)
        assertEquals(emptyMap<String, String>(), request.glossary)
    }
}
