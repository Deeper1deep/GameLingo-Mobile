package com.gamelingo.mobile.core.understanding

import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * English understanding layer: detects expressions whose meaning is NOT the
 * sum of their words (idioms, phrasal verbs, informal contractions, dialogue
 * formulas) before translation, so no engine has to guess them word by word.
 *
 * This is deliberately NOT a find-and-replace layer: a match carries the
 * expression's meaning and reference renderings, and each engine class uses
 * it the way its capabilities allow:
 *
 * - Prompt-capable engines (Gemini, TranslateGemma) receive the matches as
 *   meaning hints alongside the source line.
 * - Literal engines (Marian, ML Kit) may use a match only as safe
 *   post-processing when their output kept the expression word-for-word.
 *
 * Matching is longest-expression-first, case-insensitive, on word boundaries,
 * so "see you around" wins over "see you" and "asking" never matches "ask".
 */
class EnglishUnderstanding private constructor(
    private val expressions: List<Expression>,
    val version: Int,
) {

    enum class Layer { IDIOM, PHRASAL_VERB, INFORMAL, DIALOGUE }

    data class Expression(
        val en: String,
        val meaningEn: String,
        val arMsa: String,
        val arEgy: String,
        val layer: Layer,
    ) {
        fun arabicFor(mode: ArabicTranslationMode): String = when (mode) {
            ArabicTranslationMode.MODERN_STANDARD_ARABIC -> arMsa
            ArabicTranslationMode.EGYPTIAN_ARABIC -> arEgy
        }
    }

    data class Match(val expression: Expression, val start: Int, val end: Int)

    /** All expressions found in [source], longest first, non-overlapping. */
    fun analyze(source: String): List<Match> {
        val lower = source.lowercase()
        val found = mutableListOf<Match>()
        val claimed = BooleanArray(lower.length)
        for (expression in expressions) { // already sorted longest-first
            val needle = expression.en.lowercase()
            var index = lower.indexOf(needle)
            while (index >= 0) {
                val end = index + needle.length
                val wordBounds = (index == 0 || !lower[index - 1].isLetter()) &&
                    (end == lower.length || !lower[end].isLetter())
                var free = wordBounds
                for (i in index until end) if (claimed[i]) { free = false; break }
                if (free) {
                    found += Match(expression, index, end)
                    for (i in index until end) claimed[i] = true
                }
                index = lower.indexOf(needle, index + 1)
            }
        }
        return found.sortedBy { it.start }
    }

    /**
     * Hint block for prompt-capable engines: each detected expression with
     * its meaning and the reference rendering for [mode]. Empty when the
     * source has no known expression, so prompts never pay for noise.
     */
    fun promptHints(source: String, mode: ArabicTranslationMode): String {
        val matches = analyze(source)
        if (matches.isEmpty()) return ""
        return buildString {
            append("Expressions in this line whose meaning is not literal:")
            for (match in matches) {
                val e = match.expression
                append("\n- \"").append(e.en).append("\" means ")
                    .append(e.meaningEn)
                    .append("; render it as \"").append(e.arabicFor(mode)).append('"')
            }
        }
    }

    /**
     * Safe post-processing for literal engines (Marian, ML Kit) that never
     * see a prompt: when the WHOLE line is one known expression (tolerating
     * surrounding punctuation), the reference rendering replaces the literal
     * output wholesale. Partial-line replacement is deliberately not
     * attempted - guessing word spans inside a model's sentence is how
     * corruption happens. Returns null when the line is not exactly one
     * known expression, and the caller keeps the engine's output.
     */
    fun exactLineRendering(source: String, mode: ArabicTranslationMode): String? {
        val norm = source.trim().lowercase()
            .trimEnd('.', '!', '?', '…', ',').trim()
        if (norm.isEmpty()) return null
        val expression = expressions.firstOrNull { it.en.lowercase() == norm } ?: return null
        val rendering = expression.arabicFor(mode)
        return rendering.ifEmpty { null }
    }

    companion object {
        @Serializable
        private data class Entry(
            val en: String = "",
            @SerialName("meaning_en") val meaningEn: String = "",
            @SerialName("ar_msa") val arMsa: String = "",
            @SerialName("ar_egy") val arEgy: String = "",
        )

        @Serializable
        private data class Dataset(
            val version: Int = 1,
            val idioms: List<Entry> = emptyList(),
            @SerialName("phrasal_verbs") val phrasalVerbs: List<Entry> = emptyList(),
            val informal: List<Entry> = emptyList(),
            @SerialName("dialogue_expressions") val dialogueExpressions: List<Entry> = emptyList(),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the versioned JSON dataset (see assets/understanding/). */
        fun load(jsonText: String): EnglishUnderstanding {
            val data = json.decodeFromString(Dataset.serializer(), jsonText)
            val expressions = mutableListOf<Expression>()
            fun read(entries: List<Entry>, layer: Layer) {
                for (item in entries) {
                    val en = item.en.trim()
                    if (en.isEmpty()) continue
                    expressions += Expression(
                        en = en,
                        meaningEn = item.meaningEn.trim(),
                        arMsa = item.arMsa.trim(),
                        arEgy = item.arEgy.trim(),
                        layer = layer,
                    )
                }
            }
            read(data.idioms, Layer.IDIOM)
            read(data.phrasalVerbs, Layer.PHRASAL_VERB)
            read(data.informal, Layer.INFORMAL)
            read(data.dialogueExpressions, Layer.DIALOGUE)
            // Longest first so a longer idiom always beats its own substrings.
            expressions.sortByDescending { it.en.length }
            return EnglishUnderstanding(expressions, data.version)
        }

        fun empty(): EnglishUnderstanding = EnglishUnderstanding(emptyList(), 0)
    }
}
