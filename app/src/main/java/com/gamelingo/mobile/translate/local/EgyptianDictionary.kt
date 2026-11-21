package com.gamelingo.mobile.translate.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Updatable Egyptian game dictionary: an ASSISTANT to the local model, never a
 * replacement for it. Three cooperating layers, applied in order:
 *
 * 1. Whole-line overrides for stock game lines the NMT model reliably butchers
 *    ("Holy shit!", "Get down!"). Matches on the normalized English source.
 * 2. Profanity upgrades: known MSA softeners (تباً / اللعنة) are upgraded to the
 *    Egyptian intensity ONLY when the English source actually contains the
 *    profanity marker. The dictionary never invents profanity and never softens it.
 * 3. MSA→Egyptian lexical map with Arabic word boundaries, so known MSA
 *    renderings (الشرطة، سيارة، المال) come out as natural Egyptian
 *    (البوليس، عربية، الفلوس) without clobbering substrings of other words.
 *
 * The bundled asset is the baseline; an updated copy downloaded to app storage
 * with a higher [DictionaryData.version] wins. All entries ship in
 * assets/local_ai/egyptian_dictionary.json.
 */
class EgyptianDictionary private constructor(
    val version: Int,
    private val lineOverrides: Map<String, String>,
    private val terms: List<Term>,
    private val msaMap: List<MsaEntry>,
    private val profanityUpgrades: List<ProfanityUpgrade>,
    private val profanityMarkers: List<String>,
    private val commonGlossary: List<Term> = emptyList(),
    private val msaGlossary: List<Term> = emptyList(),
    private val egyptianGlossary: List<Term> = emptyList(),
    private val gamingSlang: List<Term> = emptyList(),
    private val characterNames: List<Term> = emptyList(),
    private val locationNames: List<Term> = emptyList(),
    private val factions: List<Term> = emptyList(),
    private val weapons: List<Term> = emptyList(),
    private val militaryTerms: List<Term> = emptyList(),
    private val missions: List<Term> = emptyList(),
) {

    @Serializable
    class Term(val en: String, val ar: String, val tags: List<String> = emptyList(), val context: String = "")

    @Serializable
    private class LineOverride(val en: String, val ar: String)

    @Serializable
    private class MsaEntry(val msa: String, val egy: String, val context: String = "")

    @Serializable
    private class ProfanityUpgrade(val marker: String, val msa: String, val egy: String)

    @Serializable
    private class DictionaryData(
        val version: Int,
        val line_overrides: List<LineOverride> = emptyList(),
        val terms: List<Term> = emptyList(),
        val msa_map: List<MsaEntry> = emptyList(),
        val profanity_upgrades: List<ProfanityUpgrade> = emptyList(),
        // Layered glossaries (dual-mode support): a term lands in the
        // mode(s) it belongs to, so an Egyptian rendering can never leak
        // into an MSA prompt and MSA phrasing never waters down Egyptian.
        val common_glossary: List<Term> = emptyList(),
        val msa_glossary: List<Term> = emptyList(),
        val egyptian_glossary: List<Term> = emptyList(),
        val gaming_slang: List<Term> = emptyList(),
        val character_names: List<Term> = emptyList(),
        val location_names: List<Term> = emptyList(),
        // World-of-the-game layers: faction/group names, weapons and gear,
        // military vocabulary, mission names. Register-neutral, so they
        // serve both Arabic modes and keep a name's rendering consistent
        // across the whole game.
        val factions: List<Term> = emptyList(),
        val weapons: List<Term> = emptyList(),
        val military_terms: List<Term> = emptyList(),
        val missions: List<Term> = emptyList(),
    )

    companion object {
        const val BUNDLED_VERSION = 1

        fun load(jsonText: String): EgyptianDictionary {
            val data = Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryData>(jsonText)
            val overrides = HashMap<String, String>()
            for (o in data.line_overrides) overrides[normalizeEn(o.en)] = o.ar
            val markers = data.profanity_upgrades.map { it.marker }.distinct()
            return EgyptianDictionary(
                version = data.version,
                lineOverrides = overrides,
                terms = data.terms,
                msaMap = data.msa_map.sortedByDescending { it.msa.length },
                profanityUpgrades = data.profanity_upgrades,
                profanityMarkers = markers,
                commonGlossary = data.common_glossary,
                msaGlossary = data.msa_glossary,
                egyptianGlossary = data.egyptian_glossary,
                gamingSlang = data.gaming_slang,
                characterNames = data.character_names,
                locationNames = data.location_names,
                factions = data.factions,
                weapons = data.weapons,
                militaryTerms = data.military_terms,
                missions = data.missions,
            )
        }

        /** Lowercase, collapse whitespace, strip surrounding punctuation for stable matching. */
        internal fun normalizeEn(text: String): String =
            text.trim().lowercase().replace(Regex("\\s+"), " ")

        /**
         * Arabic LETTERS only. The IsArabic script block also matches punctuation
         * (، ؛ ؟) which would wrongly glue a following comma to the word and block
         * legitimate replacements; diacritics stay word-internal on purpose.
         */
        private fun isArabicLetter(c: Char) = Character.isLetter(c) || c in '\u064B'..'\u0652'
    }

    /** Full-line override for a stock game line, or null when no override exists. */
    fun overrideFor(englishSource: String): String? = lineOverrides[normalizeEn(englishSource)]

    /** Renderings of dictionary terms found in [englishSource] (word-boundary match). */
    fun glossaryTerms(englishSource: String): Map<String, String> =
        glossaryTerms(
            englishSource,
            com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC,
        )

    /**
     * Mode-aware glossary: legacy terms plus the layers valid for [mode].
     * Common, gaming, and name layers serve both modes; the Egyptian layer
     * serves only Egyptian mode and the MSA layer only MSA mode, so neither
     * register can leak into the other's prompt.
     */
    fun glossaryTerms(
        englishSource: String,
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
    ): Map<String, String> {
        val normalized = normalizeEn(englishSource)
        val found = LinkedHashMap<String, String>()
        val layers = buildList {
            // Shared layers serve both modes.
            addAll(commonGlossary)
            addAll(gamingSlang)
            addAll(characterNames)
            addAll(locationNames)
            addAll(factions)
            addAll(weapons)
            addAll(militaryTerms)
            addAll(missions)
            when (mode) {
                // Legacy terms are Egyptian renderings: Egyptian mode only,
                // so a فصحى prompt never receives عامية wording.
                com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC -> {
                    addAll(terms)
                    addAll(egyptianGlossary)
                }
                com.gamelingo.mobile.data.settings.ArabicTranslationMode.MODERN_STANDARD_ARABIC ->
                    addAll(msaGlossary)
            }
        }
        for (term in layers) {
            if (containsEnPhrase(normalized, normalizeEn(term.en))) found[term.en] = term.ar
        }
        return found
    }

    /**
     * Post-processes the model's MSA output into natural Egyptian, honoring the
     * dictionary rules: preserve intensity, never invent or soften profanity,
     * respect word boundaries, longer phrases first.
     */
    fun toEgyptian(englishSource: String, msaOutput: String): String {
        overrideFor(englishSource)?.let { return it }
        var out = msaOutput
        val normalizedEn = normalizeEn(englishSource)
        // Profanity upgrades first: they only fire when the source really has the marker.
        for (upgrade in profanityUpgrades) {
            if (containsEnPhrase(normalizedEn, upgrade.marker)) {
                out = replaceArabic(out, upgrade.msa, upgrade.egy)
            }
        }
        // Then the general MSA→Egyptian lexical map, longest entries first.
        for (entry in msaMap) {
            if (entry.egy.isEmpty()) continue
            out = replaceArabic(out, entry.msa, entry.egy)
        }
        return out
    }

    /** Whether the source carries profanity the engine must preserve, never soften. */
    fun sourceHasProfanity(englishSource: String): Boolean {
        val normalized = normalizeEn(englishSource)
        return profanityMarkers.any { containsEnPhrase(normalized, it) }
    }

    /** Word-boundary phrase match for English (ASCII letters/digits). */
    internal fun containsEnPhrase(haystack: String, needle: String): Boolean {
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            val before = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
            val afterIdx = idx + needle.length
            val after = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
            if (before && after) return true
            idx = haystack.indexOf(needle, idx + 1)
        }
        return false
    }

    /** Arabic word-boundary replacement: [from] must not abut another Arabic letter. */
    private fun replaceArabic(text: String, from: String, to: String): String {
        if (from.isEmpty()) return text
        val sb = StringBuilder(text)
        var idx = sb.indexOf(from)
        while (idx >= 0) {
            val before = idx == 0 || !isArabicLetter(sb[idx - 1])
            val afterIdx = idx + from.length
            val after = afterIdx >= sb.length || !isArabicLetter(sb[afterIdx])
            if (before && after) {
                sb.replace(idx, afterIdx, to)
                idx = sb.indexOf(from, idx + to.length)
            } else {
                idx = sb.indexOf(from, idx + 1)
            }
        }
        return sb.toString()
    }
}
