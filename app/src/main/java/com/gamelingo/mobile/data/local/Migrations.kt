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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
