package com.gamelingo.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_history",
    indices = [Index(value = ["created_at"])],
)
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "source_text")
    val sourceText: String,
    @ColumnInfo(name = "translated_text")
    val translatedText: String,
    @ColumnInfo(name = "character_name")
    val characterName: String? = null,
    @ColumnInfo(name = "was_cached")
    val wasCached: Boolean = false,
    @ColumnInfo(name = "engine")
    val engine: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /**
     * [STATUS_TRANSLATED] for a normal row. Anything else marks an ORIGINAL that was captured but
     * never translated (translated_text is empty); the status says why. Never served from the cache.
     */
    @ColumnInfo(name = "status", defaultValue = "translated")
    val status: String = STATUS_TRANSLATED,
) {
    val isUntranslated: Boolean get() = status != STATUS_TRANSLATED

    companion object {
        const val STATUS_TRANSLATED = "translated"
        const val STATUS_PREFIX_UNTRANSLATED = "untranslated_"

        fun untranslatedStatus(reason: String) = STATUS_PREFIX_UNTRANSLATED + reason
    }
}
