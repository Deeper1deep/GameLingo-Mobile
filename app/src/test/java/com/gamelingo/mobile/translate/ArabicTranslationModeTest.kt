package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import com.gamelingo.mobile.data.settings.parseEnumOrDefault
import com.gamelingo.mobile.translate.local.EgyptianDictionary
import com.gamelingo.mobile.translate.local.LocalAiCandidate
import com.gamelingo.mobile.translate.local.LocalEngineChain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dual Arabic modes (MSA vs Egyptian): the twelve behaviors the brief
 * demands, at the level unit tests can honestly prove. Device-level claims
 * (capture continuity on a real phone) are covered by construction and
 * named as such in the report, not faked here.
 */
class ArabicTranslationModeTest {

    private val MSA = ArabicTranslationMode.MODERN_STANDARD_ARABIC
    private val EGY = ArabicTranslationMode.EGYPTIAN_ARABIC

    // ---- 1 & 2: the choice persists (logic level) ----

    @Test
    fun `msa choice survives a repository round trip`() = runTest {
        val repo = FakeSettingsRepository()
        repo.setArabicTranslationMode(MSA)
        assertEquals(MSA, repo.settings.first().arabicTranslationMode)
    }

    @Test
    fun `egyptian choice survives a repository round trip and is the default`() = runTest {
        val repo = FakeSettingsRepository()
        repo.setArabicTranslationMode(EGY)
        assertEquals(EGY, repo.settings.first().arabicTranslationMode)
        // A fresh install defaults to Egyptian: the project's established behavior.
        assertEquals(EGY, com.gamelingo.mobile.data.settings.AppSettings().arabicTranslationMode)
    }

    @Test
    fun `a stored mode name parses and garbage falls back to egyptian`() {
        assertEquals(MSA, parseEnumOrDefault("MODERN_STANDARD_ARABIC", EGY))
        assertEquals(EGY, parseEnumOrDefault("EGYPTIAN_ARABIC", MSA))
        assertEquals(EGY, parseEnumOrDefault("not-a-mode", EGY))
        assertEquals(EGY, parseEnumOrDefault(null, EGY))
    }

    // ---- 3: the right mode reaches every engine ----

    private class CapturingCandidate(
        override val engineId: String,
        private val available: Boolean,
        private val failure: Exception? = null,
    ) : LocalAiCandidate {
        var lastMode: ArabicTranslationMode? = null
        override fun isAvailable(): Boolean = available
        override suspend fun ensureReady() = Unit
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            lastMode = request.arabicMode
            failure?.let { throw it }
            return TranslationResult(translatedText = "نص", engineId = engineId)
        }
        override fun close() = Unit
    }

    @Test
    fun `the request carries the mode into whichever local engine answers`() = runTest {
        val gemma = CapturingCandidate("gemma", available = true, failure = RuntimeException("x"))
        val marian = CapturingCandidate("marian", available = true, failure = RuntimeException("x"))
        val mlKit = CapturingCandidate("mlkit", available = true)
        val chain = LocalEngineChain(gemma, marian, mlKit, gemmaOptIn = { true }, thermalHot = { false })
        chain.translate(TranslationRequest(text = "Run!", arabicMode = MSA))
        // Fallback never drops or rewrites the mode on the way down the chain.
        assertEquals(MSA, gemma.lastMode)
        assertEquals(MSA, marian.lastMode)
        assertEquals(MSA, mlKit.lastMode)
    }

    @Test
    fun `each prompt-capable engine gets the mandate of the request mode`() {
        val msaPrompt = ArabicStylePrompt.systemPromptFor(MSA)
        val egyPrompt = ArabicStylePrompt.systemPromptFor(EGY)
        assertNotEquals(msaPrompt, egyPrompt)
        assertTrue(msaPrompt.contains("Modern Standard Arabic"))
        assertTrue(egyPrompt.contains("Egyptian"))
        assertEquals(EgyptianDialectPrompt.SYSTEM_PROMPT, egyPrompt)
        // Gemma's local instruction follows the same split.
        assertNotEquals(
            com.gamelingo.mobile.translate.local.TranslateGemmaEngine.systemInstructionFor(MSA),
            com.gamelingo.mobile.translate.local.TranslateGemmaEngine.systemInstructionFor(EGY),
        )
    }

    // ---- 4 & 5: the cache never mixes the modes ----

    private fun contextFor(mode: ArabicTranslationMode) = CacheContext(
        aiMode = com.gamelingo.mobile.data.settings.AiMode.AUTO,
        plannedEngineId = "engine-a",
        arabicMode = mode,
        promptVersion = ArabicStylePrompt.promptVersionFor(mode),
        contextSignature = 0L,
    )

    @Test
    fun `same sentence in msa and egyptian are independent cache entries`() {
        val cache = TranslationCache()
        cache.put("Run!", contextFor(EGY), CachedTranslation("اجرى!", "engine-a"))
        cache.put("Run!", contextFor(MSA), CachedTranslation("اجرِ!", "engine-a"))
        assertEquals("اجرى!", cache.get("Run!", contextFor(EGY))?.translatedText)
        assertEquals("اجرِ!", cache.get("Run!", contextFor(MSA))?.translatedText)
    }

    @Test
    fun `an egyptian entry is never served for an msa request and vice versa`() {
        val cache = TranslationCache()
        cache.put("Run!", contextFor(EGY), CachedTranslation("اجرى!", "engine-a"))
        assertNull(cache.get("Run!", contextFor(MSA)))
        assertNotEquals(contextFor(MSA), contextFor(EGY))
    }

    // ---- 6 & 7: a mid-session mode change shows no old-mode result ----

    @Test
    fun `an in-flight result is dropped when the mode changed under it`() {
        // Queued as Egyptian, user switched to MSA before it landed: not shown.
        assertFalse(
            TranslationCoordinator.isPublishable(
                sessionRunning = true,
                capturedGeneration = 3L,
                currentGeneration = 3L,
                requestMode = EGY,
                currentMode = MSA,
            ),
        )
        // Same mode, same session: shown.
        assertTrue(
            TranslationCoordinator.isPublishable(
                sessionRunning = true,
                capturedGeneration = 3L,
                currentGeneration = 3L,
                requestMode = EGY,
                currentMode = EGY,
            ),
        )
        // A new session or a stopped one also drops it (existing guards kept).
        assertFalse(
            TranslationCoordinator.isPublishable(true, 3L, 4L, EGY, EGY),
        )
        assertFalse(
            TranslationCoordinator.isPublishable(false, 3L, 3L, EGY, EGY),
        )
    }

    // ---- 8: offline mode keeps working in both modes ----

    @Test
    fun `msa requests get an unpolished offline answer from the mlkit floor`() = runTest {
        val gemma = CapturingCandidate("gemma", available = false)
        val marian = CapturingCandidate("marian", available = false)
        val mlKit = CapturingCandidate("mlkit", available = true)
        val chain = LocalEngineChain(gemma, marian, mlKit, gemmaOptIn = { false }, thermalHot = { false })
        val result = chain.translate(TranslationRequest(text = "Where are you?", arabicMode = MSA))
        // The generic (formal) ML Kit output passes through with no Egyptian
        // dictionary polish and no +dict tag in MSA mode.
        assertEquals("mlkit", result.engineId)
        assertEquals("نص", result.translatedText)
    }

    // ---- 9: profanity survives in both modes ----

    @Test
    fun `both mode mandates preserve profanity verbatim and forbid inventing it`() {
        // Cloud (Gemini) style prompts carry the fixed anti-censorship wording.
        for (prompt in listOf(
            ArabicStylePrompt.systemPromptFor(MSA),
            ArabicStylePrompt.systemPromptFor(EGY),
        )) {
            assertTrue(prompt.contains("Never censor, omit, sanitize, soften"))
            assertTrue(prompt.contains("Do not invent profanity"))
        }
        // Gemma's per-mode instructions carry the same mandate.
        for (instruction in listOf(
            com.gamelingo.mobile.translate.local.TranslateGemmaEngine.systemInstructionFor(MSA),
            com.gamelingo.mobile.translate.local.TranslateGemmaEngine.systemInstructionFor(EGY),
        )) {
            assertTrue(instruction.contains("never soften or delete profanity"))
            assertTrue(instruction.contains("never add profanity that is not there"))
        }
        // The established Egyptian mandate keeps its exact censorship ban.
        assertTrue(EgyptianDialectPrompt.SYSTEM_PROMPT.contains("Never censor, omit, sanitize, soften"))
        assertTrue(EgyptianDialectPrompt.SYSTEM_PROMPT.contains("Do not invent profanity"))
        assertTrue(ArabicStylePrompt.MSA_SYSTEM_PROMPT.contains("Never censor, omit, sanitize, soften"))
        assertTrue(ArabicStylePrompt.MSA_SYSTEM_PROMPT.contains("Do not invent profanity"))
    }

    // ---- 10 & 11: no register leaks across the glossary layers ----

    private fun dictionary(): EgyptianDictionary = EgyptianDictionary.load(
        """
        {
          "version": 2,
          "terms": [ {"en": "car", "ar": "عربية"} ],
          "common_glossary": [ {"en": "save", "ar": "حفظ"} ],
          "msa_glossary": [ {"en": "car", "ar": "سيارة"} ],
          "egyptian_glossary": [ {"en": "money", "ar": "فلوس"} ],
          "gaming_slang": [ {"en": "checkpoint", "ar": "نقطة حفظ"} ],
          "character_names": [ {"en": "kratos", "ar": "كريتوس"} ]
        }
        """.trimIndent(),
    )

    @Test
    fun `no egyptian term leaks into an msa glossary`() {
        val found = dictionary().glossaryTerms(
            "Get in the car, hit the checkpoint, save your money, Kratos",
            MSA,
        )
        // MSA renderings + shared layers only.
        assertEquals("سيارة", found["car"])
        assertEquals("حفظ", found["save"])
        assertEquals("نقطة حفظ", found["checkpoint"])
        assertEquals("كريتوس", found["kratos"])
        assertFalse(found.containsKey("money")) // فلوس is Egyptian-layer
        assertFalse(found.containsValue("عربية")) // legacy Egyptian rendering
    }

    @Test
    fun `no msa term leaks into an egyptian glossary`() {
        val found = dictionary().glossaryTerms(
            "Get in the car, hit the checkpoint, save your money, Kratos",
            EGY,
        )
        assertEquals("عربية", found["car"]) // Egyptian legacy term wins
        assertEquals("فلوس", found["money"])
        assertEquals("حفظ", found["save"])
        assertEquals("كريتوس", found["kratos"])
        assertFalse(found.containsValue("سيارة"))
    }

    // ---- 12: fallback keeps the chosen mode (also covered above) ----

    @Test
    fun `prompt versions differ per mode so cached prompts never cross over`() {
        assertNotEquals(
            ArabicStylePrompt.promptVersionFor(MSA),
            ArabicStylePrompt.promptVersionFor(EGY),
        )
    }
}
