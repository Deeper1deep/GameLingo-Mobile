package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.ArabicTranslationMode

/**
 * Mode-aware prompts for LLM-based translation into Arabic. Two fixed
 * mandates, one per [ArabicTranslationMode]; the faithful-translation rules
 * (profanity preserved, never censored, never invented) hold in BOTH modes,
 * only the target register changes. Pure and unit-testable.
 *
 * The Egyptian mandate is the established wording from [EgyptianDialectPrompt]
 * and is not re-worded here; this object is the single entry point engines
 * use so a request's mode always selects its own prompt.
 */
object ArabicStylePrompt {

    /**
     * Cache-key prompt version per mode. Egyptian stays at
     * [EgyptianDialectPrompt.PROMPT_VERSION] (its wording is unchanged); MSA
     * starts its own line at 1. Bump a mode's version when ITS wording
     * changes in a meaning-affecting way.
     */
    fun promptVersionFor(mode: ArabicTranslationMode): Int = when (mode) {
        ArabicTranslationMode.EGYPTIAN_ARABIC -> EgyptianDialectPrompt.PROMPT_VERSION
        ArabicTranslationMode.MODERN_STANDARD_ARABIC -> MSA_PROMPT_VERSION
    }

    // v2: tense/question/negation fidelity and no-padding rules (brief 9 §5).
    const val MSA_PROMPT_VERSION = 2

    /**
     * MSA mandate: contemporary Modern Standard Arabic, natural and clear,
     * meaning and speaker tone preserved, NO Egyptian colloquialisms, names
     * and gaming terms kept, profanity carried with its original intensity
     * (never softened, never invented). Fixed wording per the project brief.
     */
    const val MSA_SYSTEM_PROMPT: String =
        "Translate the complete source text faithfully into contemporary Modern Standard Arabic " +
            "(العربية الفصحى المعاصرة). Rules:\n" +
            "- Use natural, correct, clear Arabic: sound grammar, readable phrasing, no stiff " +
            "word-for-word rendering, as a professional human game translator would write it.\n" +
            "- Preserve the original meaning, the context, and the speaker's tone (angry, " +
            "sarcastic, funny, serious).\n" +
            "- Keep the tense and modality of the source: past stays past, a plan for the future " +
            "stays future, a possibility stays a possibility. Contractions and informal English " +
            "(gonna, wanna, ain't) carry their real meaning, not a literal one.\n" +
            "- A question stays a question; a negation stays negated; never turn either into a " +
            "plain statement.\n" +
            "- Do not lengthen the translation: no added explanations, no invented information, " +
            "no repeated words.\n" +
            "- Never use Egyptian or other colloquial dialect expressions.\n" +
            "- Keep character and place names unchanged (transliterate into Arabic letters) " +
            "and keep established gaming terms from the provided glossary when present.\n" +
            "- Preserve profanity, insults, slang and offensive expressions with their original " +
            "meaning and intensity. Never censor, omit, sanitize, soften, or replace words with " +
            "asterisks. Do not invent profanity that does not exist in the source.\n" +
            "- Use the dialogue context only to resolve meaning; never repeat it.\n" +
            "- Output ONLY the Modern Standard Arabic translation of the current line, with no " +
            "explanations, no quotes and no transliteration."

    /** The system instruction for the request's mode. */
    fun systemPromptFor(mode: ArabicTranslationMode): String = when (mode) {
        ArabicTranslationMode.EGYPTIAN_ARABIC -> EgyptianDialectPrompt.SYSTEM_PROMPT
        ArabicTranslationMode.MODERN_STANDARD_ARABIC -> MSA_SYSTEM_PROMPT
    }

    /**
     * Renders the user-turn content: optional context block, names, glossary,
     * then the current line. Shared by both modes; the mode lives in the
     * system prompt and in the glossary terms the coordinator selected.
     */
    fun buildUserPrompt(request: TranslationRequest): String =
        EgyptianDialectPrompt.buildUserPrompt(request)
}
