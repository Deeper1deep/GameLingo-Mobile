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
    }

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
        """(?<![\p{IsArabic}])(لا|لم|لن|ليس|ليست|مش|ما|بدون|دون|غير|مستحيل|أبدًا|ابدًا)(?![\p{IsArabic}])""",
    )

    fun inspect(source: String, translation: String): Inspection {
        val issues = mutableListOf<Issue>()
        var text = translation

        val asksQuestion = source.trimEnd().endsWith("?")
        val answersQuestion = text.trimEnd().endsWith("؟") || text.trimEnd().endsWith("?")
        var corrected = false
        if (asksQuestion && !answersQuestion) {
            issues += Issue.QUESTION_MARK_LOST
            // Safe local fix: restore the question mark the engine dropped.
            text = text.trimEnd().trimEnd('.', '。') + "؟"
            corrected = true
        }

        if (ENGLISH_NEGATION.containsMatchIn(source) && !ARABIC_NEGATION.containsMatchIn(text)) {
            issues += Issue.NEGATION_LOST
        }

        if (text.trim().equals(source.trim(), ignoreCase = true)) {
            issues += Issue.UNTRANSLATED
        }

        return Inspection(text = text, issues = issues, corrected = corrected)
    }
}
