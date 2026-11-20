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
    const val PROMPT_VERSION = 1

    const val SYSTEM_PROMPT: String =
        "You translate English video-game subtitles into natural Egyptian colloquial Arabic " +
            "(العامية المصرية). Rules:\n" +
            "- Translate the meaning, not the words: no literal translation and no Modern " +
            "Standard Arabic stiffness.\n" +
            "- Keep jokes, sarcasm, insults and emotions intact; render them the way an " +
            "Egyptian gamer would actually say them.\n" +
            "- Keep character names unchanged (transliterate into Arabic letters).\n" +
            "- Use established gaming terms from the provided glossary when present.\n" +
            "- Use the dialogue context only to resolve meaning; never repeat it.\n" +
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

        builder.append("Translate this line:\n").append(request.text)
        return builder.toString().trimEnd()
    }
}
