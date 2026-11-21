package com.gamelingo.mobile.core.quality

import com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityGateTest {

    @Test
    fun `a lost question mark is restored locally and flagged`() {
        val result = TranslationQualityGate.inspect(
            "Well, what did you tell her?",
            "حسنًا، ماذا قلت لها",
        )
        assertEquals("حسنًا، ماذا قلت لها؟", result.text)
        assertTrue(result.corrected)
        assertTrue(result.issues.contains(Issue.QUESTION_MARK_LOST))
    }

    @Test
    fun `a kept question mark needs no correction`() {
        val result = TranslationQualityGate.inspect(
            "What did you tell her?",
            "ماذا قلت لها؟",
        )
        assertFalse(result.corrected)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `a dropped negation is flagged but never rewritten`() {
        val result = TranslationQualityGate.inspect(
            "I got no idea where he's hiding.",
            "لدي فكرة عن مكان اختبائه.",
        )
        assertTrue(result.issues.contains(Issue.NEGATION_LOST))
        // No local rewrite of meaning: the text publishes as the engine made it.
        assertFalse(result.corrected)
        assertEquals("لدي فكرة عن مكان اختبائه.", result.text)
    }

    @Test
    fun `a preserved negation passes clean`() {
        val result = TranslationQualityGate.inspect(
            "I got no idea where he's hiding.",
            "ليس لدي أدنى فكرة عن مكان اختبائه.",
        )
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `negation words inside other words do not count`() {
        // "annotated" contains "not", "ذلك" is not a negation.
        val result = TranslationQualityGate.inspect(
            "He noted the location.",
            "لاحظ الموقع.",
        )
        assertFalse(result.issues.contains(Issue.NEGATION_LOST))
    }

    @Test
    fun `an untranslated echo is flagged`() {
        val result = TranslationQualityGate.inspect(
            "Stay out of trouble.",
            "Stay out of trouble.",
        )
        assertTrue(result.issues.contains(Issue.UNTRANSLATED))
    }

    @Test
    fun `statements without negation or question stay silent`() {
        val result = TranslationQualityGate.inspect(
            "The gate is open.",
            "البوابة مفتوحة.",
        )
        assertTrue(result.issues.isEmpty())
        assertFalse(result.corrected)
    }
}
