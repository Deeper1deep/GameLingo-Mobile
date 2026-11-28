package com.gamelingo.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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

    @Test
    fun `migrate 2 to 3 keeps every row as translated and adds the status column`() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO translation_history " +
                    "(source_text, translated_text, character_name, was_cached, engine, created_at) " +
                    "VALUES ('hello', 'أهلاً', NULL, 0, 'mlkit-ondevice', 1234)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)
        db.query("SELECT source_text, translated_text, status FROM translation_history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hello", c.getString(0))
            assertEquals("أهلاً", c.getString(1))
            assertEquals("translated", c.getString(2))
        }
    }

    @Test
    fun `the full 1 to 3 path works on old data`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO translation_history " +
                    "(source_text, translated_text, character_name, was_cached, engine, created_at) " +
                    "VALUES ('a', 'ب', NULL, 0, 'e', 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *Migrations.ALL)
        db.query("SELECT status FROM translation_history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("translated", c.getString(0))
        }
        val schema = java.io.File("schemas").walkTopDown().firstOrNull { it.name == "3.json" }
        println("SCHEMA3_BEGIN")
        println(schema?.readText() ?: "no 3.json found")
        println("SCHEMA3_END")
    }

    @Test
    fun `saved originals survive closing and reopening the database and stay out of the translated set`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        fun open() = androidx.room.Room.databaseBuilder(context, GameLingoDatabase::class.java, "reopen-test.db")
            .allowMainThreadQueries().addMigrations(*Migrations.ALL).build()
        context.deleteDatabase("reopen-test.db")
        val first = open()
        kotlinx.coroutines.runBlocking {
            first.translationHistoryDao().insert(
                TranslationHistoryEntity(sourceText = "Watch out", translatedText = "", engine = "none", createdAt = 5L,
                    status = TranslationHistoryEntity.untranslatedStatus("expired")),
            )
            first.translationHistoryDao().insert(
                TranslationHistoryEntity(sourceText = "Get down", translatedText = "انزل", engine = "e", createdAt = 6L),
            )
        }
        first.close()
        val second = open()
        val rows = kotlinx.coroutines.runBlocking {
            second.translationHistoryDao().observeRecent().let { it.first() }
        }
        val actualHash = second.openHelper.readableDatabase
            .query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c -> c.moveToFirst(); c.getString(0) }
        second.close()
        val exported = java.io.File("schemas").walkTopDown().first { it.name == "3.json" }.readText()
        val debugCopy = java.io.File("src/debug/assets").walkTopDown().first { it.name == "3.json" }.readText()
        assertEquals("the schema copy used by the migration tests must equal the exported one", exported, debugCopy)
        val declared = Regex("\"identityHash\": \"([0-9a-f]+)\"").find(exported)?.groupValues?.get(1)
        println("ACTUAL_ROOM_HASH=$actualHash")
        assertEquals("ACTUAL_HASH=$actualHash", actualHash, declared)
        assertEquals(2, rows.size)
        val saved = rows.single { it.sourceText == "Watch out" }
        assertEquals("untranslated_expired", saved.status)
        assertEquals("", saved.translatedText)
        assertTrue(saved.isUntranslated)
        assertEquals("translated", rows.single { it.sourceText == "Get down" }.status)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
