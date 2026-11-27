package com.gamelingo.mobile.core.quality

/**
 * Post-engine translation quality validation: cheap, pure string heuristics
 * that catch the recurring failure shapes of literal engines without adding
 * latency (no model calls, no network) and WITHOUT retranslation loops:
 *
 * - correctable issues (lost question mark) are fixed locally and the
 *   correction is tagged in the engine id;
 * - uncorrectable suspicions (lost negation, untranslated line) are only
 *   reported through [Inspection.issues] - the line still publishes once,
 *   exactly as the engine produced it. Never a second engine call.
 */
object TranslationQualityGate {

    enum class Issue {
        /** Source asked a question; the Arabic answer lost the question mark. */
        QUESTION_MARK_LOST,

        /** Source negates (not/n't/no/never/ain't) but no Arabic negation appears. */
        NEGATION_LOST,

        /** Output equals the source: nothing was translated. */
        UNTRANSLATED,

        /** Pronoun "us" was read as the United States ("This is us" -> "الولايات المتحدة"). */
        PRONOUN_US_MISREAD,

        /** Modern Standard Arabic mode, but the output carries Egyptian colloquial words. */
        REGISTER_LEAK,

        /** Output is far longer than the source plausibly allows: padding, explaining or looping. */
        TOO_LONG,

        /** A long source produced a tiny output: content was probably cut. */
        TRUNCATED,

        /** The same word or phrase repeats in a row: a decoding loop. */
        REPETITION,

        /** The model answered with a label, note or part of the instructions instead of only the translation. */
        INSTRUCTION_LEAK,

        /** Empty or whitespace-only output. */
        EMPTY,
    }

    /**
     * Issues that mean the OUTPUT is not a usable translation (decoding loop,
     * leaked instructions, empty or runaway text). Such a result is retried
     * once and, if it stays bad, rejected: never cached, published or saved
     * as a success. The other issues (lost negation, untranslated line...)
     * stay advisory, because a short or name-only line can trip them
     * legitimately.
     */
    val CORRUPTING: Set<Issue> = setOf(Issue.EMPTY, Issue.REPETITION, Issue.INSTRUCTION_LEAK, Issue.TOO_LONG)

    fun isCorrupt(inspection: Inspection): Boolean = inspection.issues.any { it in CORRUPTING }

    data class Inspection(
        val text: String,
        val issues: List<Issue>,
        /** True when [text] was locally corrected (question mark restored). */
        val corrected: Boolean,
    )

    private val ENGLISH_NEGATION = Regex(
        """\b(not|n't|no|never|nothing|nobody|nowhere|ain't|cannot|can't|won't|don't|doesn't|didn't|isn't|aren't|wasn't|weren't|haven't|hasn't|hadn't|nah|nope)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Arabic negation particles: standalone words, not substrings of other words.
    private val ARABIC_NEGATION = Regex(
        """(?<![\p{IsArabic}])(لا|لم|لن|ليس|ليست|مش|بدون|دون|غير|مستحيل|أبدًا|ابدًا)(?![\p{IsArabic}])""",
    )

    // "ما" is both a negation particle and the question word ("ما الذي ..."),
    // so it only counts as negation when the line is not a "ما ...؟" question.
    private val ARABIC_MA = Regex("""(?<![\p{IsArabic}])ما(?![\p{IsArabic}])""")

    private fun hasArabicNegation(text: String): Boolean {
        if (ARABIC_NEGATION.containsMatchIn(text)) return true
        val trimmed = text.trim()
        val isMaQuestion = trimmed.startsWith("ما ") && (trimmed.endsWith("؟") || trimmed.endsWith("?"))
        return ARABIC_MA.containsMatchIn(text) && !isMaQuestion
    }

    // Lowercase "us" only: "US" / "U.S." / "USA" are the country and never match.
    private val PRONOUN_US = Regex("""(?<![A-Za-z])us(?![A-Za-z])""")
    private val COUNTRY_IN_SOURCE = Regex("""\b(america|american|united states|u\.s\.?|usa)\b""", RegexOption.IGNORE_CASE)
    private val COUNTRY_IN_ARABIC = Regex("الولايات المتحدة|أمريكا|امريكا|أمريكي")

    // Egyptian-only function words that never appear in natural MSA.
    private val EGYPTIAN_MARKERS = Regex(
        """(?<![\p{IsArabic}])(مش|ده|دي|دول|دلوقتي|دلوقت|عايز|عايزة|عايزين|إيه|ايه|ازاي|إزاي|كده|اللي|فين|بتاع|بتاعة|علشان|عشان|كمان|برضه|برضو)(?![\p{IsArabic}])""",
    )

    private val INSTRUCTION_LEAK = Regex(
        """(?i)(translate this line|dialogue context|^\s*(translation|الترجمة)\s*[:：]|as an ai|i cannot translate|here is the translation)""",
    )

    /** Same token 4+ times in a row, or the same 2-3 word phrase 3+ times in a row. */
    internal fun hasLoop(text: String): Boolean {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 4) return false
        var run = 1
        for (i in 1 until tokens.size) {
            run = if (tokens[i] == tokens[i - 1]) run + 1 else 1
            if (run >= 4) return true
        }
        for (n in 2..3) {
            var i = 0
            while (i + n * 3 <= tokens.size) {
                val gram = tokens.subList(i, i + n)
                if (tokens.subList(i + n, i + 2 * n) == gram && tokens.subList(i + 2 * n, i + 3 * n) == gram) return true
                i++
            }
        }
        return false
    }

    /**
     * [mode] is optional: when it is Modern Standard Arabic the gate also
     * reports Egyptian colloquial leakage. Report-only - a model's sentence is
     * never rewritten word by word here.
     */
    fun inspect(
        source: String,
        translation: String,
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode? = null,
    ): Inspection {
        val issues = mutableListOf<Issue>()
        var text = translation

        val asksQuestion = source.trimEnd().endsWith("?")
        val answersQuestion = text.trimEnd().endsWith("؟") || text.trimEnd().endsWith("?")
        var corrected = false
        if (asksQuestion && !answersQuestion) {
            issues += Issue.QUESTION_MARK_LOST
            // Safe local fix: restore the question mark the engine dropped.
            val trimmed = text.trimEnd()
            text = if (trimmed.endsWith("!")) {
                trimmed.trimEnd('!', '.', '。') + "؟!"
            } else {
                trimmed.trimEnd('.', '。', '…') + "؟"
            }
            corrected = true
        }

        if (ENGLISH_NEGATION.containsMatchIn(source) && !hasArabicNegation(text)) {
            issues += Issue.NEGATION_LOST
        }

        // A single-word source (a name, "OK") legitimately stays the same.
        val multiWord = source.trim().split(Regex("\\s+")).size >= 2
        if (multiWord && text.trim().equals(source.trim(), ignoreCase = true)) {
            issues += Issue.UNTRANSLATED
        }

        if (PRONOUN_US.containsMatchIn(source) &&
            !COUNTRY_IN_SOURCE.containsMatchIn(source) &&
            COUNTRY_IN_ARABIC.containsMatchIn(text)
        ) {
            issues += Issue.PRONOUN_US_MISREAD
        }

        if (mode == com.gamelingo.mobile.data.settings.ArabicTranslationMode.MODERN_STANDARD_ARABIC &&
            EGYPTIAN_MARKERS.containsMatchIn(text)
        ) {
            issues += Issue.REGISTER_LEAK
        }

        if (text.isBlank()) {
            issues += Issue.EMPTY
        } else {
            val kind = com.gamelingo.mobile.core.translation.LineClassifier.classify(source)
            if (com.gamelingo.mobile.core.translation.LengthPolicy.tooLong(source, text, kind)) issues += Issue.TOO_LONG
            if (com.gamelingo.mobile.core.translation.LengthPolicy.looksTruncated(source, text)) issues += Issue.TRUNCATED
            if (hasLoop(text)) issues += Issue.REPETITION
            if (INSTRUCTION_LEAK.containsMatchIn(text)) issues += Issue.INSTRUCTION_LEAK
        }

        return Inspection(text = text, issues = issues, corrected = corrected)
    }
}
