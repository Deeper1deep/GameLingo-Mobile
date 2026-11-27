package com.gamelingo.mobile.translate

/**
 * Builds the prompts for LLM-based translation into Egyptian colloquial Arabic.
 * Pure and unit-testable; the online provider (Phase 5) sends these verbatim.
 */
object EgyptianDialectPrompt {

    /**
     * Bump when the prompt wording changes in a meaning-affecting way; the
     * translation cache keys on it so entries from an older prompt style are
     * never served after an upgrade.
     */
    // 4: explicit Egyptian-only rules (no MSA, no word swapping, authentic
    // vocabulary and structure) added; older entries are dropped.
    // 2: faithful-translation mandate added (profanity preserved, never
    // censored); entries cached under version 1 predate it and are dropped.
    const val PROMPT_VERSION = 4

    // The faithful-translation mandate is fixed wording requested by the
    // user verbatim; do not soften it.
    const val SYSTEM_PROMPT: String =
        "Translate the complete source text faithfully into natural Egyptian Arabic. " +
            "Preserve profanity, insults, vulgar language, slang, and offensive expressions " +
            "with their original meaning and intensity. Never censor, omit, sanitize, soften, " +
            "or replace words with asterisks. Do not invent profanity that does not exist in " +
            "the source. Use context-aware Egyptian colloquial equivalents.\n" +
            "You translate English video-game subtitles into natural Egyptian colloquial Arabic " +
            "(العامية المصرية). Rules:\n" +
            "- Translate the meaning, not the words: no literal translation and no Modern " +
            "Standard Arabic stiffness.\n" +
            "- Keep jokes, sarcasm, insults and emotions intact; render them the way an " +
            "Egyptian gamer would actually say them.\n" +
            "- Keep character names unchanged (transliterate into Arabic letters).\n" +
            "- Use established gaming terms from the provided glossary when present.\n" +
            "- Use the dialogue context only to resolve meaning; never repeat it.\n" +
            "- Use authentic Egyptian vocabulary, expressions, sentence structure and " +
            "conversational phrasing, as spoken by people in Egypt.\n" +
            "- Do not translate into Modern Standard Arabic and do not use formal Arabic " +
            "vocabulary or grammar unless the source dialogue is itself formal.\n" +
            "- Do not merely replace a few words with Egyptian equivalents: the whole sentence " +
            "must sound Egyptian, but do not force slang where it would sound unnatural.\n" +
            "- Output ONLY the Egyptian Arabic translation of the current line, with no " +
            "explanations, no quotes and no transliteration."

    /**
     * Renders the user-turn content: optional context block, names, glossary, then the
     * current line. Empty sections are omitted entirely.
     */
    fun buildUserPrompt(request: TranslationRequest): String {
        val builder = StringBuilder()

        if (request.contextLines.isNotEmpty()) {
            builder.append("Dialogue context (do not translate):\n")
            request.contextLines.forEach { line -> builder.append("- ").append(line).append('\n') }
            builder.append('\n')
        }

        if (request.characterNames.isNotEmpty()) {
            builder.append("Character names: ")
                .append(request.characterNames.joinToString(", "))
                .append('\n')
        }

        if (request.glossary.isNotEmpty()) {
            builder.append("Glossary:\n")
            request.glossary.forEach { (term, rendering) ->
                builder.append("- ").append(term).append(" = ").append(rendering).append('\n')
            }
        }

        // Understanding hints: non-literal expressions with their established
        // meaning, so the model renders the idiom, never the words.
        if (request.understandingHints.isNotEmpty()) {
            builder.append(request.understandingHints).append('\n')
        }

        builder.append("Translate this line:\n").append(request.text)
        return builder.toString().trimEnd()
    }
}
