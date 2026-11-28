package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRecorderTest {

    private class FakeStore : HistoryRecorder.Store {
        val rows = linkedMapOf<Long, TranslationHistoryEntity>()
        private var next = 1L
        override suspend fun insert(entry: TranslationHistoryEntity): Long {
            val id = next++; rows[id] = entry; return id
        }
        override suspend fun update(id: Long, entry: TranslationHistoryEntity) { rows[id] = entry }
        override suspend fun findRecentDuplicate(source: String, translated: String, since: Long): Long? =
            rows.entries.firstOrNull {
                it.value.sourceText == source && it.value.translatedText == translated && it.value.createdAt >= since
            }?.key
    }

    private val noId = -1L
    private fun entry(src: String, ar: String, at: Long) =
        TranslationHistoryEntity(sourceText = src, translatedText = ar, engine = "e", createdAt = at)

    @Test fun `the same subtitle id updates its row instead of adding one`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(7, noId, entry("Get down", "انزل", 1_000))
        r.record(7, noId, entry("Get down now", "انزل دلوقتي", 2_000))
        assertEquals(1, s.rows.size)
        assertEquals("Get down now", s.rows.values.single().sourceText)
    }

    @Test fun `the same line under a new subtitle id is a new entry even inside the window`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(1, noId, entry("Get down", "انزل", 1_000))
        r.record(2, noId, entry("Get down", "انزل", 5_000))
        assertEquals(2, s.rows.size)
    }

    @Test fun `the same pair much later is a new occurrence`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(1, noId, entry("Get down", "انزل", 1_000))
        r.record(2, noId, entry("Get down", "انزل", 1_000 + HistoryRecorder.DUPLICATE_WINDOW_MILLIS + 1))
        assertEquals(2, s.rows.size)
    }

    @Test fun `different lines are separate rows`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(1, noId, entry("Hello", "أهلا", 1_000))
        r.record(2, noId, entry("Goodbye", "مع السلامة", 1_500))
        assertEquals(2, s.rows.size)
    }

    @Test fun `lines without a subtitle id still skip a fresh duplicate`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(noId, noId, entry("Hello", "أهلا", 1_000))
        r.record(noId, noId, entry("Hello", "أهلا", 2_000))
        assertEquals(1, s.rows.size)
    }

    @Test fun `an untranslated original is kept with its reason and no translation`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.recordUntranslated(5, noId, "Watch out", "overflow", 1_000)
        val row = s.rows.values.single()
        assertEquals("Watch out", row.sourceText)
        assertEquals("", row.translatedText)
        assertEquals("untranslated_overflow", row.status)
        assertEquals(true, row.isUntranslated)
    }

    @Test fun `an untranslated save never overwrites a translated row of the same subtitle`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.record(7, noId, entry("Get down", "انزل", 1_000))
        r.recordUntranslated(7, noId, "Get down", "stopped", 1_000)
        assertEquals(1, s.rows.size)
        assertEquals("انزل", s.rows.values.single().translatedText)
        assertEquals("translated", s.rows.values.single().status)
    }

    @Test fun `a later translation upgrades the saved original and a repeat of the text is its own row`() = runBlocking {
        val s = FakeStore(); val r = HistoryRecorder(s)
        r.recordUntranslated(7, noId, "Get down", "expired", 1_000)
        r.recordUntranslated(8, noId, "Get down", "expired", 5_000)
        assertEquals("same text, other appearance: its own row", 2, s.rows.size)
        r.recordUntranslated(7, noId, "Get down", "failed", 1_000)
        assertEquals("the same appearance is not saved twice", 2, s.rows.size)
        r.record(7, noId, entry("Get down", "انزل", 6_000))
        assertEquals(2, s.rows.size)
        assertEquals("translated", s.rows[1L]!!.status)
        assertEquals("انزل", s.rows[1L]!!.translatedText)
        assertEquals("untranslated_expired", s.rows[2L]!!.status)
    }
}
