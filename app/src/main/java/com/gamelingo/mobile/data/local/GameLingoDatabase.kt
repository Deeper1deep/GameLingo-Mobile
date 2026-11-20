package com.gamelingo.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TranslationHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GameLingoDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao
}
