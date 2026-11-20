package com.gamelingo.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real MIGRATION_1_2 on a JVM-hosted SQLite via Robolectric, using
 * the committed schema JSONs (app/schemas, mirrored into src/test/assets) as
 * the source of truth. Proves the migration keeps every history row and adds
 * exactly the created_at index. Pinned to SDK 35: the newest Robolectric
 * supports up to 35 and SQLite behaves identically for this test. A plain
 * Application stands in for GameLingoApp: the migration does not need the
 * app, and the real one starts ML Kit, which has no JVM context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GameLingoDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 1 to 2 preserves rows and adds the created_at index`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO translation_history " +
                    "(source_text, translated_text, character_name, was_cached, engine, created_at) " +
                    "VALUES ('hello', 'أهلاً', NULL, 0, 'mlkit-ondevice', 1234)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT source_text, translated_text, created_at FROM translation_history").use { cursor ->
            assertTrue("history row must survive the migration", cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals("أهلاً", cursor.getString(1))
            assertEquals(1234L, cursor.getLong(2))
        }

        val indexNames = mutableListOf<String>()
        db.query("PRAGMA index_list('translation_history')").use { cursor ->
            while (cursor.moveToNext()) indexNames += cursor.getString(1)
        }
        assertTrue(
            "expected index_translation_history_created_at in $indexNames",
            indexNames.contains("index_translation_history_created_at"),
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
