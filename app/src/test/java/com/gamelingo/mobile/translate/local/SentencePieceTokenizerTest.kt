package com.gamelingo.mobile.translate.local

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Verifies the pure-Kotlin unigram tokenizer against the exact encoder ids the
 * reference MarianTokenizer (transformers, shipping model files) produced for
 * the 40-line Egyptian test set. A mismatch here means every translated line on
 * device is garbage, so this test pins token-for-token equality, not a sample.
 */
class SentencePieceTokenizerTest {

    @Test
    fun `kotlin tokenizer matches the reference ids on the whole test set`() {
        var checked = 0
        for (case in referenceCases()) {
            val ids = tokenizer.encode(case.en)
            assertEquals(
                "tokenizer diverged on line ${case.id}: ${case.en}",
                case.inputIds,
                ids.toList(),
            )
            checked++
        }
        assertTrue("reference set must not be empty", checked >= 40)
    }

    @Test
    fun `extra whitespace collapses like the reference tokenizer`() {
        assertEquals(tokenizer.encode("kill him").toList(), tokenizer.encode("  kill   him ").toList())
    }

    @Test
    fun `every encoding ends with EOS`() {
        val ids = tokenizer.encode("never saw this piece before")
        assertEquals(EOS_ID, ids.last())
    }

    @Test
    fun `decode round-trips plain english`() {
        val text = "kill him now"
        assertEquals(text, tokenizer.decode(tokenizer.encode(text)))
    }

    private data class ReferenceCase(val id: Int, val en: String, val inputIds: List<Int>)

    companion object {
        private const val EOS_ID = 0

        private lateinit var tokenizer: SentencePieceTokenizer

        @JvmStatic
        @BeforeClass
        fun loadTokenizer() {
            tokenizer = SentencePieceTokenizer.load(resourceText("enar_tokenizer.json"))
        }

        private fun referenceCases(): List<ReferenceCase> {
            val root = Json.parseToJsonElement(resourceText("tokenizer_reference.json")).jsonArray
            return root.map { element ->
                val obj = element.jsonObject
                ReferenceCase(
                    id = obj.getValue("id").jsonPrimitive.int,
                    en = obj.getValue("en").jsonPrimitive.content,
                    inputIds = obj.getValue("input_ids").jsonArray.map { it.jsonPrimitive.int },
                )
            }
        }

        private fun resourceText(name: String): String {
            val stream = SentencePieceTokenizerTest::class.java.classLoader
                ?.getResourceAsStream(name)
                ?: error("test resource missing: $name")
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        }
    }
}
