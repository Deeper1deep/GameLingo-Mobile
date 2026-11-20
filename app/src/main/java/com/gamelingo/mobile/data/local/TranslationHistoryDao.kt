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

    @Query("SELECT COUNT(*) FROM translation_history")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM translation_history")
    suspend fun clear()
}
