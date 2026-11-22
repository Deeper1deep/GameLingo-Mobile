package com.gamelingo.mobile.core.quality

import com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue
import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefGateTest {

    @Test
    fun `pronoun us rendered as the United States is flagged`() {
        val r = TranslationQualityGate.inspect("This is us.", "هذه هي الولايات المتحدة.")
        assertTrue(Issue.PRONOUN_US_MISREAD in r.issues)
    }

    @Test
    fun `pronoun us rendered as we is not flagged`() {
        val r = TranslationQualityGate.inspect("This is us.", "هذا نحن.")
        assertFalse(Issue.PRONOUN_US_MISREAD in r.issues)
    }

    @Test
    fun `real US country mention is never flagged`() {
        val r = TranslationQualityGate.inspect("Do you know where US troops are?", "هل تعرف أين القوات الأمريكية؟")
        assertFalse(Issue.PRONOUN_US_MISREAD in r.issues)
    }

    @Test
    fun `egyptian words in modern standard arabic are flagged`() {
        val r = TranslationQualityGate.inspect(
            "I don't know", "أنا مش عارف", ArabicTranslationMode.MODERN_STANDARD_ARABIC,
        )
        assertTrue(Issue.REGISTER_LEAK in r.issues)
    }

    @Test
    fun `egyptian words in egyptian mode are fine`() {
        val r = TranslationQualityGate.inspect(
            "I don't know", "أنا مش عارف", ArabicTranslationMode.EGYPTIAN_ARABIC,
        )
        assertFalse(Issue.REGISTER_LEAK in r.issues)
    }

    @Test
    fun `clean modern standard arabic is not flagged`() {
        val r = TranslationQualityGate.inspect(
            "I have no idea", "ليس لدي أدنى فكرة", ArabicTranslationMode.MODERN_STANDARD_ARABIC,
        )
        assertEquals(emptyList<Issue>(), r.issues)
    }
}
