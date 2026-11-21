package com.gamelingo.mobile.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditFixesQualityGateTest {

    @Test
    fun `ma question word is not counted as negation`() {
        val r = TranslationQualityGate.inspect("Don't you know?", "ما الذي تعرفه؟")
        assertTrue(TranslationQualityGate.Issue.NEGATION_LOST in r.issues)
    }

    @Test
    fun `ma as a plain statement still counts as negation`() {
        val r = TranslationQualityGate.inspect("I don't know", "ما أعرف")
        assertFalse(TranslationQualityGate.Issue.NEGATION_LOST in r.issues)
    }

    @Test
    fun `restored question mark keeps exclamation as question-exclamation`() {
        val r = TranslationQualityGate.inspect("Are you serious?", "هل انت جاد!")
        assertEquals("هل انت جاد؟!", r.text)
    }

    @Test
    fun `restored question mark replaces trailing period`() {
        val r = TranslationQualityGate.inspect("Where is he?", "اين هو.")
        assertEquals("اين هو؟", r.text)
    }

    @Test
    fun `single word source is not flagged untranslated`() {
        val r = TranslationQualityGate.inspect("OK", "OK")
        assertFalse(TranslationQualityGate.Issue.UNTRANSLATED in r.issues)
    }

    @Test
    fun `multi word identical output is flagged untranslated`() {
        val r = TranslationQualityGate.inspect("Go home", "Go home")
        assertTrue(TranslationQualityGate.Issue.UNTRANSLATED in r.issues)
    }
}
