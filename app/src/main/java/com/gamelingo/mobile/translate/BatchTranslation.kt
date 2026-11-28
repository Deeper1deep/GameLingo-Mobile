package com.gamelingo.mobile.translate

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
            "Output ONLY a JSON array of strings: exactly one string per numbered line, in the same " +
            "order. Never merge, split, skip, reorder or repeat lines. Do not add numbers or " +
            "comments inside the strings."

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

    /**
     * The translations in input order, or null unless the answer is a JSON array
     * of exactly [expected] non-blank strings. Surrounding code fences or prose
     * are tolerated; anything that makes the count or the alignment doubtful is not.
     */
    fun parse(raw: String, expected: Int): List<String>? {
        if (expected <= 0) return null
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val element = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
        val array = element as? JsonArray ?: return null
        if (array.size != expected) return null
        val out = ArrayList<String>(expected)
        for (item in array) {
            val primitive = item as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val text = primitive.content.trim()
            if (text.isEmpty()) return null
            out.add(text)
        }
        return out
    }
}
