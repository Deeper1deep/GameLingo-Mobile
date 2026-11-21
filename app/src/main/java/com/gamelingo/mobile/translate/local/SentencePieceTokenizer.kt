package com.gamelingo.mobile.translate.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure-Kotlin SentencePiece unigram tokenizer for the downloadable EN→AR Marian model.
 *
 * The model cannot ship the native sentencepiece library without an NDK build, so
 * this implements the same algorithm (unigram Viterbi with Metaspace
 * pre-tokenization) in Kotlin. Verified token-for-token against the reference
 * `transformers` MarianTokenizer output for the whole game-dialogue test set
 * (see SentencePieceTokenizerTest).
 *
 * Encoding: text -> pieces -> model input ids (+ EOS).
 * Decoding: model output ids -> pieces -> detokenized Arabic text.
 */
class SentencePieceTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val modelIds: IntArray,
    private val decodeTable: Array<String?>,
    private val byFirstChar: Map<Char, IntArray>,
    private val unkModelId: Int,
    private val eosModelId: Int,
    private val padModelId: Int,
) {

    companion object {
        private const val METASPACE = '▁'
        private const val MAX_PIECE_CHARS = 32
        private const val MAX_INPUT_TOKENS = 480
        /** Score given to the fallback one-char unknown transition. */
        private const val UNK_SCORE = -20f

        /** Parses the enar_tokenizer.json asset shipped inside the model bundle. */
        fun load(jsonText: String): SentencePieceTokenizer {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val enc = root.getValue("enc").jsonArray
            val dec = root.getValue("dec").jsonArray
            val n = enc.size
            val pieces = Array(n) { i -> enc[i].jsonArray[0].jsonPrimitive.content }
            val scores = FloatArray(n) { i -> enc[i].jsonArray[1].jsonPrimitive.float }
            val modelIds = IntArray(n) { i -> enc[i].jsonArray[2].jsonPrimitive.int }
            val decodeTable = Array(dec.size) { i ->
                val el = dec[i]
                if (el is JsonNull) null else el.jsonPrimitive.content
            }
            val groups = HashMap<Char, MutableList<Int>>()
            for (i in 0 until n) {
                val first = pieces[i].firstOrNull() ?: continue
                groups.getOrPut(first) { ArrayList() }.add(i)
            }
            return SentencePieceTokenizer(
                pieces = pieces,
                scores = scores,
                modelIds = modelIds,
                decodeTable = decodeTable,
                byFirstChar = groups.mapValues { it.value.toIntArray() },
                unkModelId = root.getValue("unk_id").jsonPrimitive.int,
                eosModelId = root.getValue("eos_id").jsonPrimitive.int,
                padModelId = root.getValue("pad_id").jsonPrimitive.int,
            )
        }
    }

    /** Encodes [text] into model input ids, EOS-terminated, matching MarianTokenizer. */
    fun encode(text: String): IntArray {
        val normalized = normalize(text)
        val tokens = viterbi(normalized)
        val count = minOf(tokens.size, MAX_INPUT_TOKENS)
        val out = IntArray(count + 1)
        for (i in 0 until count) out[i] = tokens[i]
        out[count] = eosModelId
        return out
    }

    /** Decodes model output ids back to Arabic text. Stops at EOS/PAD. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosModelId || id == padModelId) break
            when (val piece = decodeTable.getOrNull(id)) {
                null, "<s>" -> Unit
                "<unk>" -> sb.append('⁇')
                else -> sb.append(piece)
            }
        }
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    /** Metaspace pre-tokenization: collapse whitespace, prefix the word marker. */
    private fun normalize(text: String): String {
        val collapsed = text.trim().replace(Regex("\\s+"), " ")
        return METASPACE + collapsed.replace(' ', METASPACE)
    }

    /**
     * Unigram Viterbi segmentation. Known pieces starting at each position compete
     * on cumulative score; an unknown single-char transition keeps the lattice
     * connected for characters the model never saw (emitted as the unk id, exactly
     * like the reference tokenizer maps uncovered pieces to <unk>).
     */
    private fun viterbi(text: String): List<Int> {
        val len = text.length
        val best = DoubleArray(len + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(len + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until len) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var matched = false
            val candidates = byFirstChar[text[i]]
            if (candidates != null) {
                for (pieceIdx in candidates) {
                    val piece = pieces[pieceIdx]
                    if (piece.length > MAX_PIECE_CHARS) continue
                    if (text.startsWith(piece, i)) {
                        matched = true
                        val end = i + piece.length
                        val score = best[i] + scores[pieceIdx]
                        if (score > best[end]) {
                            best[end] = score
                            back[end] = pieceIdx
                        }
                    }
                }
            }
            if (!matched) {
                val score = best[i] + UNK_SCORE
                if (score > best[i + 1]) {
                    best[i + 1] = score
                    back[i + 1] = Int.MIN_VALUE
                }
            }
        }
        val out = ArrayList<Int>()
        var pos = len
        while (pos > 0) {
            when (val pieceIdx = back[pos]) {
                -1 -> break
                Int.MIN_VALUE -> {
                    out.add(unkModelId)
                    pos -= 1
                }
                else -> {
                    out.add(modelIds[pieceIdx])
                    pos -= pieces[pieceIdx].length
                }
            }
        }
        out.reverse()
        return out
    }
}
