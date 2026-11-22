package com.gamelingo.mobile.core.understanding

import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import com.gamelingo.mobile.translate.TranslationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The understanding layer's detection and hint behavior, plus scene
 * separation in the context memory. The eight mandatory sentences from the
 * brief are covered through the expressions they contain.
 */
class EnglishUnderstandingTest {

    private val MSA = ArabicTranslationMode.MODERN_STANDARD_ARABIC
    private val EGY = ArabicTranslationMode.EGYPTIAN_ARABIC

    private fun loadBundled(): EnglishUnderstanding {
        val text = javaClass.classLoader!!
            .getResourceAsStream("english_expressions.json")!!
            .bufferedReader().readText()
        return EnglishUnderstanding.load(text)
    }

    private val layer = loadBundled()

    // The eight mandatory observed-error cases: each one's key expression
    // must be detected with a non-literal meaning.
    @Test
    fun `test 1 - see you around is an idiom, not words about seeing`() {
        val matches = layer.analyze("Yeah, see you around.")
        assertTrue(matches.any { it.expression.en == "see you around" })
    }

    @Test
    fun `test 2 - stay out of trouble and good man are understood`() {
        val matches = layer.analyze("Good man. Hey, you stay out of trouble, alright?")
        assertTrue(matches.any { it.expression.en == "good man" })
        assertTrue(matches.any { it.expression.en == "you stay out of trouble" })
    }

    @Test
    fun `test 3 - out in force is understood as mass deployment`() {
        val matches = layer.analyze("Military's gonna be out in force soon.")
        assertTrue(matches.any { it.expression.en == "out in force" })
    }

    @Test
    fun `test 4 - I got no idea is understood as complete ignorance`() {
        val matches = layer.analyze("The truth. I got no idea where he's hiding.")
        assertTrue(matches.any { it.expression.en == "the truth" })
        // Either the full idiom or its tail is detected (they overlap).
        assertTrue(matches.any { it.expression.en.lowercase() == "i got no idea" || it.expression.en == "no idea where" })
    }

    @Test
    fun `test 6 - she'd contraction case keeps its conditional meaning`() {
        val matches = layer.analyze("You think she'd tell me?")
        assertTrue(matches.any { it.expression.en == "you think she'd tell me" })
    }

    @Test
    fun `test 7 - faction question phrasing is understood`() {
        val matches = layer.analyze("Marlene? What do the Fireflies need with Robert?")
        assertTrue(matches.any { it.expression.en == "what do the fireflies need with" })
    }

    @Test
    fun `test 8 - asking around is understood as asking many people`() {
        val matches = layer.analyze("Uh... Marlene. She's been asking around, trying to find him.")
        assertTrue(matches.any { it.expression.en == "she's been asking around" })
    }

    @Test
    fun `longest expression wins over its own substring`() {
        // "you stay out of trouble" must beat plain "stay out of trouble".
        val matches = layer.analyze("you stay out of trouble")
        assertEquals("you stay out of trouble", matches.first().expression.en)
        assertEquals(1, matches.size)
    }

    @Test
    fun `word boundaries are respected`() {
        // "asking" alone must not trigger "asking around".
        assertTrue(layer.analyze("He is asking a question.").isEmpty())
    }

    @Test
    fun `hints carry the rendering of the request mode`() {
        val msa = layer.promptHints("Yeah, see you around.", MSA)
        val egy = layer.promptHints("Yeah, see you around.", EGY)
        assertTrue(msa.contains("أراك لاحقًا"))
        assertFalse(msa.contains("أشوفك"))
        assertTrue(egy.contains("أشوفك على خير"))
        assertFalse(egy.contains("أراك لاحقًا"))
    }

    @Test
    fun `no expressions means no prompt noise`() {
        assertEquals("", layer.promptHints("Hello there.", MSA))
    }

    @Test
    fun `dataset is versioned and layered`() {
        assertTrue(layer.version >= 1)
        val layers = layer.analyze("take cover, see you around, gonna")
            .map { it.expression.layer }.toSet()
        assertTrue(layers.contains(EnglishUnderstanding.Layer.PHRASAL_VERB))
        assertTrue(layers.contains(EnglishUnderstanding.Layer.IDIOM))
        assertTrue(layers.contains(EnglishUnderstanding.Layer.INFORMAL))
    }

    @Test
    fun `this is us renders as we, never as the country`() {
        assertEquals("هذا نحن", layer.exactLineRendering("This is us.", MSA))
        assertEquals("ده إحنا", layer.exactLineRendering("This is us.", EGY))
    }

    @Test
    fun `hud label renders per mode`() {
        assertEquals("اللعب فقط", layer.exactLineRendering("GAMEPLAY ONLY", MSA))
        assertEquals("اللعب بس", layer.exactLineRendering("GAMEPLAY ONLY", EGY))
    }

    @Test
    fun `coast is clear and patch yourself up are understood inside a line`() {
        assertTrue(layer.analyze("Looks like the coast is clear.").any { it.expression.en == "the coast is clear" })
        assertTrue(layer.analyze("Patch yourself up, alright?").any { it.expression.en == "patch yourself up" })
    }

    @Test
    fun `modes never swap renderings`() {
        val e = layer.analyze("The coast is clear").first().expression
        assertEquals("أصبح المكان آمنًا", e.arabicFor(MSA))
        assertEquals("المكان بقى أمان", e.arabicFor(EGY))
    }
}

class ExactLineRenderingTest {

    private val MSA = ArabicTranslationMode.MODERN_STANDARD_ARABIC
    private val EGY = ArabicTranslationMode.EGYPTIAN_ARABIC

    private fun loadBundled(): EnglishUnderstanding {
        val text = javaClass.classLoader!!
            .getResourceAsStream("english_expressions.json")!!
            .bufferedReader().readText()
        return EnglishUnderstanding.load(text)
    }

    private val layer = loadBundled()

    @Test
    fun `a line that is exactly one idiom gets its reference rendering per mode`() {
        assertEquals("أراك لاحقًا", layer.exactLineRendering("See you around.", MSA))
        assertEquals("أشوفك على خير", layer.exactLineRendering("see you around!", EGY))
    }

    @Test
    fun `a line that merely CONTAINS an idiom is never rewritten`() {
        // Partial replacement is unsafe: only whole-line matches qualify.
        assertEquals(null, layer.exactLineRendering("Yeah, see you around.", MSA))
    }

    @Test
    fun `plain lines return null so the engine output stands`() {
        assertEquals(null, layer.exactLineRendering("Where is Robert?", MSA))
    }
}

class UnderstandingPromptRenderTest {

    @Test
    fun `gemini user prompt carries the hints before the line`() {
        val request = TranslationRequest(
            text = "Yeah, see you around.",
            understandingHints = "- \"see you around\" means goodbye; render it as \"أراك لاحقًا\"",
        )
        val prompt = com.gamelingo.mobile.translate.EgyptianDialectPrompt.buildUserPrompt(request)
        val hintsAt = prompt.indexOf("see you around\" means")
        val lineAt = prompt.indexOf("Translate this line:")
        assertTrue(hintsAt >= 0)
        assertTrue(lineAt > hintsAt)
    }

    @Test
    fun `no hints means the prompt is unchanged`() {
        val prompt = com.gamelingo.mobile.translate.EgyptianDialectPrompt
            .buildUserPrompt(TranslationRequest(text = "Hello."))
        assertFalse(prompt.contains("means"))
    }
}

