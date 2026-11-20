package com.gamelingo.mobile.translate

import javax.inject.Inject
import javax.inject.Singleton

/** One cached line: the translation plus the engine that produced it. */
data class CachedTranslation(
    val translatedText: String,
    val engineId: String,
)

/**
 * Small in-memory least-recently-used cache for repeated subtitle lines.
 * Game dialogue repeats itself (menus, combat barks), so a hit saves an
 * online request or an on-device pass. Keys are normalised (trimmed,
 * whitespace-collapsed, lowercased) so trivial OCR jitter still hits.
 */
@Singleton
class TranslationCache @Inject constructor() {

    private val entries = object : LinkedHashMap<String, CachedTranslation>(
        MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedTranslation>?,
        ): Boolean = size > MAX_ENTRIES
    }

    fun get(text: String): CachedTranslation? = synchronized(entries) {
        entries[normalize(text)]
    }

    fun put(text: String, translation: CachedTranslation) {
        synchronized(entries) {
            entries[normalize(text)] = translation
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** Visible for tests. */
    internal fun size(): Int = synchronized(entries) { entries.size }

    internal fun normalize(text: String): String =
        text.trim().replace(WHITESPACE, " ").lowercase()

    private companion object {
        const val MAX_ENTRIES = 200
        val WHITESPACE = Regex("\\s+")
    }
}
