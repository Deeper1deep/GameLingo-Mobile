package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.local.TranslationHistoryEntity

/**
 * Writes the final translation of a subtitle to history, once.
 *
 * - The same subtitle appearance ([subtitleId]) that is published again
 *   (better text, a cache hit after a retry) UPDATES its row; it never adds
 *   a second one.
 * - A new subtitle id is a new occurrence and always gets its own row, even
 *   for identical text (the same line shown again later stays a new entry).
 * - Records without a subtitle id: the same source and translation within
 *   [DUPLICATE_WINDOW_MILLIS] is skipped.
 *
 * Only accepted translations reach this class: rejected or failed output
 * throws before publication. Nothing existing is deleted or rewritten.
 */
class HistoryRecorder(private val store: Store) {

    interface Store {
        suspend fun insert(entry: TranslationHistoryEntity): Long
        suspend fun update(id: Long, entry: TranslationHistoryEntity)
        suspend fun findRecentDuplicate(source: String, translated: String, since: Long): Long?
    }

    private val rowBySubtitle = object : LinkedHashMap<Long, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > MAX_TRACKED
    }

    suspend fun record(subtitleId: Long, noId: Long, entry: TranslationHistoryEntity) {
        val known = if (subtitleId != noId) synchronized(rowBySubtitle) { rowBySubtitle[subtitleId] } else null
        if (known != null) {
            store.update(known, entry)
            return
        }
        // A distinct subtitle id is a distinct occurrence: it is always its own
        // entry, even for identical text. Only id-less records are de-duplicated.
        val duplicate = if (subtitleId == noId) store.findRecentDuplicate(
            entry.sourceText, entry.translatedText, entry.createdAt - DUPLICATE_WINDOW_MILLIS,
        ) else null
        if (duplicate != null) {
            if (subtitleId != noId) synchronized(rowBySubtitle) { rowBySubtitle[subtitleId] = duplicate }
            return
        }
        val id = store.insert(entry)
        if (subtitleId != noId && id > 0) synchronized(rowBySubtitle) { rowBySubtitle[subtitleId] = id }
    }

    companion object {
        const val DUPLICATE_WINDOW_MILLIS = 20_000L
        const val MAX_TRACKED = 64

        fun over(dao: TranslationHistoryDao): HistoryRecorder = HistoryRecorder(
            object : Store {
                override suspend fun insert(entry: TranslationHistoryEntity) = dao.insert(entry)
                override suspend fun update(id: Long, entry: TranslationHistoryEntity) =
                    dao.updateEntry(id, entry.sourceText, entry.translatedText, entry.engine, entry.wasCached, entry.createdAt)
                override suspend fun findRecentDuplicate(source: String, translated: String, since: Long) =
                    dao.findRecentDuplicate(source, translated, since)
            },
        )
    }
}
