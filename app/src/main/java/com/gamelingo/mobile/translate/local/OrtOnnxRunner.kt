package com.gamelingo.mobile.translate.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of [OnnxRunner]. One OrtEnvironment and one session
 * pair per model load; the engine holds this for the whole session and serializes
 * access (sessions are not thread-safe). The merged decoder recomputes the prefix
 * each step, which is acceptable for subtitle-length lines (<=128 tokens).
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

    /** Loads both sessions. Idempotent; call again after [close] to reload. */
    @Synchronized
    fun open() {
        if (encoder != null && decoder != null) return
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            runCatching { addConfigEntry("session.disable_quant_qdq", "1") }
        }
        encoder = environment.createSession(encoderFile.absolutePath, options)
        decoder = environment.createSession(decoderFile.absolutePath, options)
        env = environment
    }

    private class OrtEncoderState(
        val hiddenStates: OnnxTensor,
        val attentionMask: OnnxTensor,
        val env: OrtEnvironment,
    ) : EncoderState

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
        session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { results ->
            val hidden = results.get(0)
            // Detach the encoder output from the run results so it outlives them.
            val hiddenCopy = OnnxTensor.createTensor(
                environment,
                (hidden as OnnxTensor).floatBuffer,
                hidden.info.shape,
            )
            idsTensor.close()
            return OrtEncoderState(hiddenCopy, maskTensor, environment)
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
            session.run(
                mapOf(
                    "input_ids" to idsTensor,
                    "encoder_hidden_states" to state.hiddenStates,
                    "encoder_attention_mask" to state.attentionMask,
                ),
            ).use { results ->
                val logitsTensor = results.get(0) as OnnxTensor
                val shape = logitsTensor.info.shape // [1, seq, vocab]
                val vocab = shape[2].toInt()
                val seq = shape[1].toInt()
                val all = FloatArray((shape[0] * shape[1] * shape[2]).toInt())
                logitsTensor.floatBuffer.get(all)
                val last = FloatArray(vocab)
                System.arraycopy(all, (seq - 1) * vocab, last, 0, vocab)
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
        encoder = null
        decoder = null
    }

    companion object {
        /**
         * Four threads: enough for int8 gemm without starving the game, OCR and
         * capture pipelines on an 8-core mid-range SoC. Measured on device later.
         */
        const val DEFAULT_THREADS = 4
    }
}
