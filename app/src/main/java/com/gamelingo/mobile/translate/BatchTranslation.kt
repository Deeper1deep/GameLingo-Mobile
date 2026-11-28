package com.gamelingo.mobile.translate

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The provider answered a batch, but not with exactly one usable translation
 * per input line. Nothing from such an answer is ever shown: the caller
 * translates those lines one by one instead, so a translation can never land
 * on the wrong sentence.
 */
class BatchMisalignedException(message: String) : IOException(message)

/**
 * Pure helpers for translating several subtitle lines in ONE provider request.
 *
 * Why: the free tier limits requests per minute and per day, not tokens, so
 * grouping the lines that are already waiting costs far fewer requests than
 * one request per line, without skipping any line.
 */
object BatchFormat {
    /** Most lines sent in one request. */
    const val MAX_LINES = 8

    /** Most source characters sent in one request. */
    const val MAX_CHARS = 1_200

    /** Output budget per line, plus a fixed allowance for the array syntax. */
    fun maxOutputTokens(lineCount: Int): Int = (256 + 160 * (lineCount - 1)).coerceAtMost(2_048)

    /**
     * Added to the normal system prompt. The base prompt says "output only the
     * translation of the current line"; for a batch the output is a JSON array
     * that holds one translation per numbered line.
     */
    const val SYSTEM_SUFFIX: String =
        "\nBATCH MODE for this request: the user message holds several numbered subtitle lines. " +
            "Translate EACH line on its own, with every rule above applied to every line. " +
            "Output ONLY a JSON array with one object per numbered line: " +
            "{\"n\": the line number, \"src\": the first 3 words of that source line copied exactly, " +
            "\"t\": the translation of that line only}. Use every number exactly once. " +
            "Never merge, split, skip or repeat lines. Do not add numbers or comments inside \"t\"."

    fun userPrompt(
        lines: List<String>,
        contextLines: List<String>,
        glossary: Map<String, String>,
        /** Meaning hints per line, keyed by the 1-based line number; empty hints are skipped. */
        hints: Map<Int, String> = emptyMap(),
    ): String {
        val builder = StringBuilder()
        if (contextLines.isNotEmpty()) {
            builder.append("Dialogue context (do not translate):\n")
            contextLines.forEach { builder.append("- ").append(it).append('\n') }
            builder.append('\n')
        }
        if (glossary.isNotEmpty()) {
            builder.append("Glossary (use these renderings):\n")
            glossary.forEach { (en, ar) -> builder.append("- ").append(en).append(" = ").append(ar).append('\n') }
            builder.append('\n')
        }
        builder.append("Translate these ").append(lines.size).append(" lines, in order:\n")
        lines.forEachIndexed { index, line ->
            builder.append(index + 1).append(". ").append(line.replace('\n', ' ')).append('\n')
        }
        hints.filterValues { it.isNotBlank() }.forEach { (number, hint) ->
            builder.append("\nMeaning hints for line ").append(number).append(":\n").append(hint.trim())
        }
        return builder.toString().trimEnd()
    }

    /** Words of the source line the model must copy back, so every answer can be matched to its line. */
    const val ECHO_WORDS = 3

    private fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Per input line: its translation, or null when that entry cannot be trusted and the line must be
     * translated on its own. The answer is a JSON array of objects {"n": line number, "src": first words
     * of the source line copied back, "t": translation}. An entry is used only when
     * - "n" is an integer between 1 and the number of lines,
     * - that "n" appears exactly once (duplicates reject every copy),
     * - "src" is the start of THAT line's source text (same words, case and punctuation ignored),
     * - "t" is a non-blank string.
     * Entries are placed by their number, so a different order is harmless. A missing number leaves that line
     * null. Returns null when the answer is not a JSON array at all.
     *
     * Limit: this catches shifted, duplicated, dropped and mislabeled entries. It cannot prove that the
     * translation text inside a correctly labeled entry belongs to that line: a model that copies the right
     * echo but writes another line's translation would pass (the quality gate catches only some of those).
     */
    fun parseEntries(raw: String, sources: List<String>): List<String?>? {
        if (sources.isEmpty()) return null
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull() as? JsonArray
            ?: return null
        val byNumber = HashMap<Int, MutableList<JsonObject>>()
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val n = (obj["n"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: continue
            if (n < 1 || n > sources.size) continue
            byNumber.getOrPut(n) { ArrayList() }.add(obj)
        }
        return sources.mapIndexed { index, source ->
            val entries = byNumber[index + 1] ?: return@mapIndexed null
            if (entries.size != 1) return@mapIndexed null
            val obj = entries.first()
            val echo = tokens((obj["src"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapIndexed null)
            val sourceTokens = tokens(source)
            if (echo.isEmpty() || echo.size > sourceTokens.size) return@mapIndexed null
            if (echo != sourceTokens.take(echo.size)) return@mapIndexed null
            if (echo.size < minOf(ECHO_WORDS, sourceTokens.size)) return@mapIndexed null
            val t = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return@mapIndexed null
            t.ifEmpty { null }
        }
    }
}
