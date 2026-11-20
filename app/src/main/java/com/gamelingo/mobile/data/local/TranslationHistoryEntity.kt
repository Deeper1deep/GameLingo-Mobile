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
)
