package com.gamelingo.mobile.translate

/**
 * The compact system instructions for the on-device model, kept next to the
 * cloud prompts (ArabicStylePrompt, EgyptianDialectPrompt) instead of inside the
 * engine. They are shorter on purpose (a 4B local model), but PromptPolicyTest
 * pins that every prompt keeps the same policy clauses, so they cannot drift apart.
 */
object LocalPrompts {
    /**
     * Dialect mandate: natural spoken Egyptian, preserve profanity intensity,
     * never soften, never invent, keep gaming terms per glossary, no MSA unless
     * the source is formal, no explanations - the translated line only.
     */
    const val SYSTEM_INSTRUCTION =
        "You are a professional English to Egyptian Arabic translator for video game subtitles. " +
            "Translate into natural spoken Egyptian colloquial Arabic (عامية مصرية واقعية), exactly how a " +
            "real Egyptian gamer would say it, never formal MSA. Rules: keep the exact profanity intensity " +
            "of the source (never soften or delete profanity, never add profanity that is not there); keep " +
            "sarcasm and tone; keep character names transliterated in Arabic letters; keep gaming terms " +
            "natural; translate idioms by meaning, never word-for-word; never omit any part of the line. " +
            "Use authentic Egyptian vocabulary, expressions and sentence structure; do not translate into " +
            "Modern Standard Arabic; do not merely swap a few words for Egyptian ones, but do not force " +
            "slang where it would sound unnatural. " +
            "Output ONLY the translated line, with no explanations, no notes, no quotes."

    /**
     * MSA mandate: contemporary Modern Standard Arabic, natural and
     * correct, no colloquialisms; the profanity policy is identical to
     * the Egyptian one (preserve intensity, never soften, never invent).
     */
    const val SYSTEM_INSTRUCTION_MSA =
        "You are a professional English to Modern Standard Arabic translator for video game subtitles. " +
            "Translate into contemporary Modern Standard Arabic (العربية الفصحى المعاصرة): natural, " +
            "grammatically sound, clear phrasing, never stiff word-for-word rendering, and never " +
            "Egyptian or other colloquial expressions. Rules: keep the tense of the source, " +
            "a question stays a question and a negation stays negated; keep the exact profanity intensity " +
            "of the source (never soften or delete profanity, never add profanity that is not there); keep " +
            "sarcasm and tone; keep character names transliterated in Arabic letters; keep gaming terms " +
            "natural; translate idioms by meaning, never word-for-word; never omit any part of the line. " +
            "Output ONLY the translated line, with no explanations, no notes, no quotes."

    fun systemInstructionFor(
        mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode,
    ): String = when (mode) {
        com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC ->
            SYSTEM_INSTRUCTION + " " + PromptComposer.GUARD_MODULE
        com.gamelingo.mobile.data.settings.ArabicTranslationMode.MODERN_STANDARD_ARABIC ->
            SYSTEM_INSTRUCTION_MSA + " " + PromptComposer.GUARD_MODULE
    }
}
