package com.gamelingo.mobile.translate.local

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of [OnnxRunner]. One OrtEnvironment and one session
 * pair per model load; the engine holds this for the whole session and serializes
 * access (sessions are not thread-safe).
 *
 * The real OptimaMT merged decoder has 25 inputs, not 3: besides input_ids,
 * encoder_hidden_states and encoder_attention_mask it requires the 24
 * past_key_values.* cache tensors and a use_cache_branch flag. This runner feeds
 * the no-cache contract: empty past tensors + use_cache_branch=false, so the
 * decoder recomputes the full prefix each step (correct for subtitle-length
 * lines, <=128 tokens). Verified against the real model files: greedy output
 * matches the HuggingFace Marian baseline.
 *
 * `session.disable_quant_qdq` fuses the quantized ops into the fast path
 * (2.3-2.5x faster, half memory, identical tokens per the model conversion docs).
 */
class OrtOnnxRunner(
    private val encoderFile: File,
    private val decoderFile: File,
    private val threadCount: Int = DEFAULT_THREADS,
) : OnnxRunner {

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    /** The merged decoder's mandatory no-cache inputs, built once per load. */
    private var pastTensors: List<Pair<String, OnnxTensor>> = emptyList()
    private var useCacheBranch: OnnxTensor? = null

    /**
     * Loads both sessions and the static no-cache inputs. Idempotent; call
     * again after [close] to reload. Transactional: if any step fails, every
     * resource opened so far is released before the exception propagates, so
     * a failed load never leaks a session or doubles native memory on retry.
     */
    @Synchronized
    fun open() {
        if (encoder != null && decoder != null) return
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            runCatching { addConfigEntry("session.disable_quant_qdq", "1") }
        }
        val newPast = mutableListOf<Pair<String, OnnxTensor>>()
        var newUseCache: OnnxTensor? = null
        var newEncoder: OrtSession? = null
        var newDecoder: OrtSession? = null
        try {
            newEncoder = environment.createSession(encoderFile.absolutePath, options)
            newDecoder = environment.createSession(decoderFile.absolutePath, options)
            for (layer in 0 until DECODER_LAYERS) {
                for (kind in listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")) {
                    newPast.add(
                        "past_key_values.$layer.$kind" to OnnxTensor.createTensor(
                            environment, FloatBuffer.allocate(0), EMPTY_PAST_SHAPE,
                        ),
                    )
                }
            }
            newUseCache = OnnxTensor.createTensor(
                environment, ByteBuffer.wrap(byteArrayOf(0)), USE_CACHE_SHAPE, OnnxJavaType.BOOL,
            )
        } catch (failure: Throwable) {
            runCatching { newEncoder?.close() }
            runCatching { newDecoder?.close() }
            newPast.forEach { (_, tensor) -> runCatching { tensor.close() } }
            runCatching { newUseCache?.close() }
            throw failure
        }
        env = environment
        encoder = newEncoder
        decoder = newDecoder
        pastTensors = newPast
        useCacheBranch = newUseCache
    }

    private class OrtEncoderState(
        val hiddenStates: OnnxTensor,
        val attentionMask: OnnxTensor,
        val env: OrtEnvironment,
    ) : EncoderState {
        /** Releases both native tensors; idempotent (OnnxTensor.close is). */
        override fun close() {
            runCatching { hiddenStates.close() }
            runCatching { attentionMask.close() }
        }
    }

    @Synchronized
    override fun encode(inputIds: IntArray): EncoderState {
        open()
        val environment = env!!
        val session = encoder!!
        val ids = LongArray(inputIds.size) { inputIds[it].toLong() }
        val mask = LongArray(inputIds.size) { 1L }
        val idsTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()),
        )
        val maskTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(mask), longArrayOf(1, mask.size.toLong()),
        )
        try {
            session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { results ->
                val hidden = results.get(0) as OnnxTensor
                // Detach the encoder output with a REAL copy: createTensor(buffer)
                // wraps the source buffer's memory, which closing `results` frees;
                // the old wrap-only approach read freed native memory (use-after-free).
                val copy = FloatArray(hidden.info.shape.fold(1L) { acc, dim -> acc * dim }.toInt())
                hidden.floatBuffer.get(copy)
                val hiddenCopy = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(copy),
                    hidden.info.shape,
                )
                idsTensor.close()
                return OrtEncoderState(hiddenCopy, maskTensor, environment)
            }
        } catch (failure: Throwable) {
            // Never leak the input tensors on a failed encode; the mask is
            // ours until the state object takes it over on success.
            runCatching { idsTensor.close() }
            runCatching { maskTensor.close() }
            throw failure
        }
    }

    @Synchronized
    override fun decodeStep(encoderState: EncoderState, decoderIds: IntArray): FloatArray {
        val state = encoderState as OrtEncoderState
        val environment = state.env
        val session = decoder!!
        val ids = LongArray(decoderIds.size) { decoderIds[it].toLong() }
        val idsTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()),
        )
        try {
            val inputs = HashMap<String, OnnxTensor>(pastTensors.size + 4)
            inputs["input_ids"] = idsTensor
            inputs["encoder_hidden_states"] = state.hiddenStates
            inputs["encoder_attention_mask"] = state.attentionMask
            pastTensors.forEach { (name, tensor) -> inputs[name] = tensor }
            inputs["use_cache_branch"] = useCacheBranch!!
            session.run(inputs).use { results ->
                val logitsTensor = results.get(0) as OnnxTensor
                val shape = logitsTensor.info.shape // [1, seq, vocab]
                val vocab = shape[2].toInt()
                val seq = shape[1].toInt()
                // Read ONLY the last position's logits. Copying the whole
                // [1, seq, vocab] tensor per step allocated ~32 MB per token
                // at the 128-token budget - a GC storm mid-dialogue.
                val last = FloatArray(vocab)
                val buffer = logitsTensor.floatBuffer
                buffer.position((seq - 1) * vocab)
                buffer.get(last)
                return last
            }
        } finally {
            idsTensor.close()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        pastTensors.forEach { (_, tensor) -> runCatching { tensor.close() } }
        runCatching { useCacheBranch?.close() }
        pastTensors = emptyList()
        useCacheBranch = null
        encoder = null
        decoder = null
    }

    companion object {
        /**
         * Four threads: enough for int8 gemm without starving the game, OCR and
         * capture pipelines on an 8-core mid-range SoC. Measured on device later.
         */
        const val DEFAULT_THREADS = 4

        /** The OptimaMT merged decoder has 6 layers; each needs 4 past tensors. */
        private const val DECODER_LAYERS = 6

        /** [batch, heads, past_seq, head_dim]; past_seq=0 marks "no cache". */
        private val EMPTY_PAST_SHAPE = longArrayOf(1, 8, 0, 64)
        private val USE_CACHE_SHAPE = longArrayOf(1)
    }
}
