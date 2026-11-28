package com.gamelingo.mobile.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchFormatTest {
    private val sources = listOf("Get down now", "They are coming", "Go")

    private fun entry(n: Int, src: String, t: String) = "{\"n\":$n,\"src\":\"$src\",\"t\":\"$t\"}"

    private fun answer(vararg e: String) = e.joinToString(",", "[", "]")

    @Test fun `a faithful answer maps every entry to its line`() {
        val raw = answer(entry(1, "Get down now", "انزل"), entry(2, "They are coming", "جايين"), entry(3, "Go", "يلا"))
        assertEquals(listOf("انزل", "جايين", "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `a fenced answer and loose echo case and punctuation are accepted`() {
        val raw = "```json\n" + answer(entry(1, "get DOWN", "انزل"), entry(2, "They are coming!", "جايين"), entry(3, "go", "يلا")) + "\n```"
        assertEquals(listOf("انزل", "جايين", "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `out of order entries are placed by their number`() {
        val raw = answer(entry(3, "Go", "يلا"), entry(1, "Get down now", "انزل"), entry(2, "They are coming", "جايين"))
        assertEquals(listOf("انزل", "جايين", "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `a missing number leaves only that line null`() {
        val raw = answer(entry(1, "Get down now", "انزل"), entry(3, "Go", "يلا"))
        assertEquals(listOf("انزل", null, "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `duplicate numbers reject every copy`() {
        val raw = answer(entry(1, "Get down now", "انزل"), entry(1, "Get down now", "اقعد"), entry(2, "They are coming", "جايين"), entry(3, "Go", "يلا"))
        assertEquals(listOf(null, "جايين", "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `a mismatched echo rejects that entry, a shifted answer rejects the lines it shifted`() {
        // Translations shifted by one line: the echo copied from the wrong source never matches.
        val shifted = answer(entry(1, "They are coming", "جايين"), entry(2, "Go", "يلا"), entry(3, "Get down now", "انزل"))
        assertEquals(listOf(null, null, null), BatchFormat.parseEntries(shifted, sources))
        val one = answer(entry(1, "Get down now", "انزل"), entry(2, "They were coming", "جايين"), entry(3, "Go", "يلا"))
        assertEquals(listOf("انزل", null, "يلا"), BatchFormat.parseEntries(one, sources))
    }

    @Test fun `out of range, non integer, blank or non string fields are rejected`() {
        val raw = "[{\"n\":0,\"src\":\"Get down now\",\"t\":\"x\"},{\"n\":9,\"src\":\"Go\",\"t\":\"x\"}," +
            "{\"n\":\"1\",\"src\":\"Get down now\",\"t\":\"x\"},{\"n\":2,\"src\":\"They are coming\",\"t\":\"  \"}," +
            "{\"n\":3,\"src\":\"Go\",\"t\":5},\"junk\"]"
        assertEquals(listOf(null, null, null), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `echo with too few words is rejected`() {
        val raw = answer(entry(1, "Get", "انزل"), entry(2, "They are coming", "جايين"), entry(3, "Go", "يلا"))
        assertEquals(listOf(null, "جايين", "يلا"), BatchFormat.parseEntries(raw, sources))
    }

    @Test fun `not an array at all gives null`() {
        assertNull(BatchFormat.parseEntries("sorry, I can't", sources))
        assertNull(BatchFormat.parseEntries("[1,2", sources))
    }

    @Test fun `the same text under the right number and echo is still trusted, the known limit`() {
        // Documents the limit: a correct label with another line's translation text cannot be detected here.
        val raw = answer(entry(1, "Get down now", "جايين"), entry(2, "They are coming", "انزل"), entry(3, "Go", "يلا"))
        assertEquals(listOf("جايين", "انزل", "يلا"), BatchFormat.parseEntries(raw, sources))
    }
}
