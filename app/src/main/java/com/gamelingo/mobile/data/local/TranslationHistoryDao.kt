package com.gamelingo.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TranslationHistoryEntity): Long

    @Query("SELECT * FROM translation_history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<TranslationHistoryEntity>>

    @Query(
        "SELECT * FROM translation_history " +
            "WHERE source_text LIKE '%' || :query || '%' " +
            "OR translated_text LIKE '%' || :query || '%' " +
            "OR character_name LIKE '%' || :query || '%' " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    fun search(query: String, limit: Int = 200): Flow<List<TranslationHistoryEntity>>

    @Query("SELECT COUNT(*) FROM translation_history")
    fun observeCount(): Flow<Int>

    @Query(
        "UPDATE translation_history SET source_text = :source, translated_text = :translated, " +
            "engine = :engine, was_cached = :cached, created_at = :createdAt, status = :status WHERE id = :id",
    )
    suspend fun updateEntry(
        id: Long,
        source: String,
        translated: String,
        engine: String,
        cached: Boolean,
        createdAt: Long,
        status: String = TranslationHistoryEntity.STATUS_TRANSLATED,
    )

    /** Id of a row with exactly this pair written at or after [since], or null. */
    @Query(
        "SELECT id FROM translation_history WHERE source_text = :source " +
            "AND translated_text = :translated AND created_at >= :since LIMIT 1",
    )
    suspend fun findRecentDuplicate(source: String, translated: String, since: Long): Long?

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM translation_history")
    suspend fun clear()
}
