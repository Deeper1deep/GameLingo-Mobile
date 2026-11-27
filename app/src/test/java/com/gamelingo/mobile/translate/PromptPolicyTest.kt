package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every prompt, cloud or local, must carry the same policy clauses. */
class PromptPolicyTest {
    private fun all(): Map<String, String> = ArabicTranslationMode.entries.flatMap { mode ->
        listOf(
            "cloud-$mode" to PromptComposer.systemPrompt(mode, "Hello there, how are you?"),
            "local-$mode" to LocalPrompts.systemInstructionFor(mode),
        )
    }.toMap()

    @Test fun `every prompt preserves profanity and returns the translation only`() {
        all().forEach { (name, p) ->
            assertTrue("$name lacks profanity policy", p.contains("profanity", ignoreCase = true))
            assertTrue("$name lacks output-only rule", p.contains("Output ONLY"))
        }
    }

    @Test fun `every prompt treats screen text as data`() {
        all().forEach { (name, p) -> assertTrue("$name lacks the guard", p.contains(PromptComposer.GUARD_MODULE)) }
    }
}
