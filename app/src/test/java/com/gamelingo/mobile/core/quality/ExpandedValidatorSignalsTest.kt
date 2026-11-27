package com.gamelingo.mobile.core.quality

import com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedValidatorSignalsTest {
    private fun issues(src: String, out: String) = TranslationQualityGate.inspect(src, out).issues

    @Test fun `chinese or cyrillic output for an english line is language mixing and corrupting`() {
        val i = TranslationQualityGate.inspect("Get down, they are coming!", "انزل 快点 جايين")
        assertTrue(Issue.FOREIGN_SCRIPT in i.issues)
        assertTrue(TranslationQualityGate.isCorrupt(i))
        assertTrue(Issue.FOREIGN_SCRIPT in issues("Stay here", "Оставайся هنا"))
    }

    @Test fun `a source that itself contains the script is not flagged`() {
        assertFalse(Issue.FOREIGN_SCRIPT in issues("The sign says 日本", "اللافتة مكتوب عليها 日本"))
    }

    @Test fun `a mostly english output for a long english line is flagged but advisory`() {
        val i = TranslationQualityGate.inspect("We need to move before they find us here", "We need to move before ده they find us")
        assertTrue(Issue.SOURCE_FRAGMENTS in i.issues)
        assertFalse(TranslationQualityGate.isCorrupt(i))
    }

    @Test fun `names and intentional english terms are not flagged`() {
        assertFalse(Issue.SOURCE_FRAGMENTS in issues("Joel and Ellie reached the Fireflies base", "جويل وإيلي وصلوا قاعدة Fireflies"))
        assertFalse(Issue.SOURCE_FRAGMENTS in issues("Joel", "جويل"))
        assertFalse(Issue.SOURCE_FRAGMENTS in issues("OK", "تمام"))
    }

    @Test fun `a lost number is reported, arabic-indic digits count as the same number`() {
        assertTrue(Issue.NUMBER_LOST in issues("You have 3 minutes left", "فاضل عندك دقايق"))
        assertFalse(Issue.NUMBER_LOST in issues("You have 3 minutes left", "فاضل عندك ٣ دقايق"))
        assertFalse(Issue.NUMBER_LOST in issues("You have 3 minutes left", "فاضل عندك 3 دقايق"))
    }

    @Test fun `a good translation raises none of the new signals`() {
        val i = issues("They took the 2 keys and ran", "خدوا المفتاحين 2 وجروا")
        assertFalse(Issue.FOREIGN_SCRIPT in i)
        assertFalse(Issue.SOURCE_FRAGMENTS in i)
        assertFalse(Issue.NUMBER_LOST in i)
    }
}
