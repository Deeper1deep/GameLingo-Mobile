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
 * online request or an on-device pass.
 *
 * An entry is stored and looked up under the normalized text AND the full
 * [CacheContext] (AI mode, planned engine, prompt version, context signature,
 * language pair), so a change in mode, engine, prompt style or dialogue
 * context never serves a stale or misattributed answer. The stored engine id
 * is additionally checked against [CacheContext.accepts], so an online result
 * is never presented as a local one or vice versa.
 */
@Singleton
class TranslationCache @Inject constructor() {

    private class Entry(
        val translation: CachedTranslation,
        val context: CacheContext,
    )

    // Keyed by text AND context: the same sentence in MSA and Egyptian (or
    // under different prompt versions or dialogue context) are independent
    // entries; a later put in one mode must never evict the other's answer.
    private val entries = object : LinkedHashMap<Pair<String, CacheContext>, Entry>(
        MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, CacheContext>, Entry>?,
        ): Boolean = size > MAX_ENTRIES
    }

    fun get(text: String, context: CacheContext): CachedTranslation? = synchronized(entries) {
        val entry = entries[normalize(text) to context] ?: return null
        if (!context.accepts(entry.translation.engineId)) {
            return null
        }
        entry.translation
    }

    fun put(text: String, context: CacheContext, translation: CachedTranslation) {
        synchronized(entries) {
            entries[normalize(text) to context] = Entry(translation, context)
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
