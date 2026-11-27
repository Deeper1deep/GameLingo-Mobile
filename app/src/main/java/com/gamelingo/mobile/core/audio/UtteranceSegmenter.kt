package com.gamelingo.mobile.core.audio

/**
 * Energy-based voice activity detection and utterance segmentation over
 * 16-bit mono PCM frames. Pure Kotlin: no Android, no model, no network.
 *
 * It decides only WHERE speech starts and ends. It does not recognise words;
 * a separate speech recogniser is needed for that, and none is bundled.
 * Energy VAD cannot tell speech from loud music or effects, so game audio
 * with a heavy score will produce false segments. That is a known limit.
 */
class UtteranceSegmenter(
    private val sampleRate: Int = 16_000,
    private val frameMillis: Int = 30,
    /** RMS (0..32767) above which a frame counts as voiced. */
    private val voicedRms: Double = 900.0,
    private val minSpeechMillis: Int = 240,
    /** Silence that closes an utterance. */
    private val hangoverMillis: Int = 450,
    private val maxUtteranceMillis: Int = 12_000,
) {
    sealed interface Event {
        data class Started(val utteranceId: Long, val atMillis: Long) : Event
        /** A still-growing utterance, for partial recognition. */
        data class Partial(val utteranceId: Long, val pcm: ShortArray, val atMillis: Long) : Event
        data class Finished(val utteranceId: Long, val pcm: ShortArray, val startMillis: Long, val endMillis: Long) : Event
    }

    val frameSamples: Int get() = sampleRate * frameMillis / 1000

    private var nextId = 1L
    private var activeId = 0L
    private var startMillis = 0L
    private var voicedMillis = 0
    private var silenceMillis = 0
    private var lastPartialAt = 0L
    private var buffer = ShortArray(0)
    private var used = 0

    fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (v in frame) sum += v.toDouble() * v.toDouble()
        return Math.sqrt(sum / frame.size)
    }

    /** Feed one frame (any length; time advances by [frameMillis]). Returns events it caused. */
    fun accept(frame: ShortArray, nowMillis: Long): List<Event> {
        val out = ArrayList<Event>(2)
        val voiced = rms(frame) >= voicedRms
        if (activeId == 0L) {
            if (!voiced) return out
            activeId = nextId++
            startMillis = nowMillis
            voicedMillis = 0
            silenceMillis = 0
            lastPartialAt = nowMillis
            used = 0
            out += Event.Started(activeId, nowMillis)
        }
        append(frame)
        if (voiced) { voicedMillis += frameMillis; silenceMillis = 0 } else silenceMillis += frameMillis
        val length = nowMillis - startMillis + frameMillis
        if (silenceMillis >= hangoverMillis || length >= maxUtteranceMillis) {
            val id = activeId
            val pcm = buffer.copyOf(used)
            val enough = voicedMillis >= minSpeechMillis
            activeId = 0L
            used = 0
            if (enough) out += Event.Finished(id, pcm, startMillis, nowMillis + frameMillis)
        } else if (voiced && nowMillis - lastPartialAt >= PARTIAL_EVERY_MILLIS && voicedMillis >= minSpeechMillis) {
            lastPartialAt = nowMillis
            out += Event.Partial(activeId, buffer.copyOf(used), nowMillis)
        }
        return out
    }

    private fun append(frame: ShortArray) {
        if (used + frame.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, used + frame.size, 4096))
        System.arraycopy(frame, 0, buffer, used, frame.size)
        used += frame.size
    }

    companion object { const val PARTIAL_EVERY_MILLIS = 1_000L }
}
