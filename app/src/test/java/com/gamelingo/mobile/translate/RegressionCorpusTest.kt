package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.quality.TranslationQualityGate
import com.gamelingo.mobile.core.understanding.EnglishUnderstanding
import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import com.gamelingo.mobile.translate.local.EgyptianDictionary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The deep-engine regression corpus: the 8 mandatory observed-error cases
 * plus 115 varied cases (natural dialogue, idioms, phrasal verbs, informal
 * contractions, questions, negation, tenses, pronouns, names, military,
 * weapons, profanity, short lines, context, scene transitions).
 *
 * HONEST SCOPE: no translation engine runs on the JVM, so this harness does
 * not claim translated-output quality. It proves the knowledge layers feed
 * every engine correctly:
 *  - every flagged expression IS detected (understanding layer);
 *  - the renderings/pinned terms needed for the expected output exist in
 *    the shipped datasets, per mode (understanding renderings + dictionary);
 *  - corpus flags are self-consistent (question/negation markers);
 *  - the quality gate's negation/question detectors agree with the flags.
 * Engine-output axes (grammar, naturalness) are measured only where an
 * engine actually runs and are reported as such, never fabricated here.
 */
class RegressionCorpusTest {

    @Serializable
    private data class Case(
        val id: String,
        val category: String,
        val source: String,
        val detect: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("expect_msa_tokens") val expectMsa: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("expect_egy_tokens") val expectEgy: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("is_question") val isQuestion: Boolean? = null,
        @kotlinx.serialization.SerialName("has_negation") val hasNegation: Boolean? = null,
        val context: List<String> = emptyList(),
        val note: String = "",
    )

    @Serializable
    private data class Corpus(val version: Int, val cases: List<Case>)

    private fun resource(name: String): String = javaClass.classLoader!!
        .getResourceAsStream(name)!!.bufferedReader().readText()

    private val corpus = Json { ignoreUnknownKeys = true }
        .decodeFromString(Corpus.serializer(), resource("regression_corpus.json"))
    private val understanding = EnglishUnderstanding.load(resource("english_expressions.json"))
    private val dictionary = EgyptianDictionary.load(resource("egyptian_dictionary.json"))

    private val MSA = ArabicTranslationMode.MODERN_STANDARD_ARABIC
    private val EGY = ArabicTranslationMode.EGYPTIAN_ARABIC

    @Test
    fun `corpus has the 8 mandatory cases and at least 100 more`() {
        assertTrue(corpus.cases.count { it.category == "mandatory" } == 8)
        assertTrue(corpus.cases.size >= 108)
    }

    @Test
    fun `every flagged expression is detected by the understanding layer`() {
        val misses = mutableListOf<String>()
        for (case in corpus.cases) {
            val found = understanding.analyze(case.source).map { it.expression.en.lowercase() }
            for (expected in case.detect) {
                if (expected.lowercase() !in found) {
                    misses += "${case.id}: '$expected' not detected in '${case.source}'"
                }
            }
        }
        if (misses.isNotEmpty()) fail(misses.joinToString("\n"))
    }

    @Test
    fun `the knowledge needed for the expected rendering exists per mode`() {
        // Collect everything the layers know about the source line: idiom
        // renderings + pinned glossary terms, in each mode. The expected
        // tokens of a correct translation must live in that knowledge;
        // engines then receive it via hints/glossary prompts.
        val misses = mutableListOf<String>()
        for (case in corpus.cases) {
            if (case.expectMsa.isEmpty() && case.expectEgy.isEmpty()) continue
            for ((mode, expected) in listOf(MSA to case.expectMsa, EGY to case.expectEgy)) {
                if (expected.isEmpty()) continue
                val knowledge = buildString {
                    understanding.analyze(case.source).forEach {
                        append(it.expression.arabicFor(mode)).append('\n')
                    }
                    dictionary.glossaryTerms(case.source, mode).values.forEach { append(it).append('\n') }
                }
                for (token in expected) {
                    if (token !in knowledge) {
                        misses += "${case.id} [$mode]: knowledge lacks '$token' for '${case.source}'"
                    }
                }
            }
        }
        if (misses.isNotEmpty()) fail(misses.joinToString("\n"))
    }

    @Test
    fun `question and negation flags agree with the quality gate`() {
        val disagreements = mutableListOf<String>()
        for (case in corpus.cases) {
            if (case.isQuestion == true && '?' !in case.source) {
                disagreements += "${case.id}: flagged question without '?'"
            }
            if (case.hasNegation == true) {
                // The gate's own detector must see the negation it will later check for.
                val probe = TranslationQualityGate.inspect(case.source, "ترجمة بلا نفي")
                if (!probe.issues.contains(TranslationQualityGate.Issue.NEGATION_LOST)) {
                    disagreements += "${case.id}: gate sees no negation in '${case.source}'"
                }
            }
        }
        if (disagreements.isNotEmpty()) fail(disagreements.joinToString("\n"))
    }

    @Test
    fun `understanding analysis stays real-time over the whole corpus`() {
        // The layer runs per line on the OCR path; it must cost microseconds,
        // not milliseconds. Generous ceiling for a shared CI machine.
        val start = System.nanoTime()
        repeat(20) { corpus.cases.forEach { understanding.analyze(it.source) } }
        val perLineMicros = (System.nanoTime() - start) / 1_000 / (20 * corpus.cases.size)
        assertTrue("analysis too slow: ${perLineMicros}us/line", perLineMicros < 2_000)
    }
}
