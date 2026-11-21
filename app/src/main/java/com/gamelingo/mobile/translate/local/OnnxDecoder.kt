package com.gamelingo.mobile.translate.local

/**
 * Inference backend behind the local translation engine. The real implementation
 * wraps ONNX Runtime sessions (device only); unit tests substitute a fake so the
 * decode loop is fully verified on the JVM.
 */
interface OnnxRunner {
    /** Runs the encoder once; returns an opaque handle consumed by [decodeStep]. */
    fun encode(inputIds: IntArray): EncoderState

    /**
     * Runs the merged decoder over [encoderState] and the full decoder prefix
     * [decoderIds]; returns next-token logits for the last position.
     */
    fun decodeStep(encoderState: EncoderState, decoderIds: IntArray): FloatArray

    fun close()
}

/**
 * Opaque per-request encoder output (hidden states + attention mask).
 * Implementations holding native resources must release them in [close];
 * the default is a no-op for pure-JVM fakes. [GreedyDecoder] always closes
 * the state, so one request can never leak native tensors.
 */
interface EncoderState {
    fun close() {}
}

/**
 * Greedy autoregressive decode loop shared by device and tests: starts from the
 * decoder start token, appends the argmax each step, stops at EOS or the length
 * budget. Cancellation is checked every step so a long decode never stalls the
 * translation queue past its timeout.
 */
class GreedyDecoder(
    private val runner: OnnxRunner,
    private val decoderStartId: Int,
    private val eosId: Int,
    private val maxOutputTokens: Int = 128,
    private val isCancelled: () -> Boolean = { false },
) {

    class DecodeCancelledException : Exception("decode_cancelled")

    /** Returns the generated token ids WITHOUT the leading start token or trailing EOS. */
    fun generate(inputIds: IntArray): IntArray {
        val encoderState = runner.encode(inputIds)
        try {
            val prefix = ArrayList<Int>(16)
            prefix.add(decoderStartId)
            repeat(maxOutputTokens) {
                if (isCancelled()) throw DecodeCancelledException()
                val logits = runner.decodeStep(encoderState, prefix.toIntArray())
                var argmax = 0
                var best = Float.NEGATIVE_INFINITY
                for (i in logits.indices) {
                    if (logits[i] > best) {
                        best = logits[i]
                        argmax = i
                    }
                }
                if (argmax == eosId) return prefix.drop(1).toIntArray()
                prefix.add(argmax)
            }
            return prefix.drop(1).toIntArray()
        } finally {
            // The encoder output holds native tensors; release them on every
            // path (success, EOS, cancellation, engine error) or each request
            // leaks native memory until the whole model is unloaded.
            runCatching { encoderState.close() }
        }
    }
}
