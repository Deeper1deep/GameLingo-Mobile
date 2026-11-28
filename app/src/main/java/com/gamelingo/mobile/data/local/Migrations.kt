package com.gamelingo.mobile.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real Room migrations, one per schema change. A migration must preserve
 * every existing row; dropping user data here is never acceptable.
 */
object Migrations {

    /** v1 -> v2: add the created_at index. Purely additive; no row is touched. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_translation_history_created_at` " +
                    "ON `translation_history` (`created_at`)",
            )
        }
    }

    /** v2 -> v3: add the status column; every existing row becomes 'translated'. Additive, no row is touched. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `translation_history` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'translated'")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
