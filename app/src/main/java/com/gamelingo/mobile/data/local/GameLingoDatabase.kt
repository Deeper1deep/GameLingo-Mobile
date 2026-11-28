package com.gamelingo.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema history:
 *  - v1: translation_history table.
 *  - v2: index on created_at (the history screen sorts by it on every open).
 *  - v3: status column (translated, or untranslated_<reason> for a saved original with no translation).
 *
 * Every future schema change MUST ship a real Migration in [Migrations] plus a
 * migration test. Destructive fallback is banned: it would silently wipe the
 * user's translation history on upgrade.
 */
@Database(
    entities = [TranslationHistoryEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class GameLingoDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao
}
