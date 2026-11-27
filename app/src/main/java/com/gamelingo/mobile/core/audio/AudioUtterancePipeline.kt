package com.gamelingo.mobile.core.audio

/**
 * Frames in, finished utterance texts out: VAD gating ([UtteranceSegmenter]),
 * recognition ([AsrEngine]) and duplicate suppression against on-screen text.
 * Pure Kotlin so the flow is testable with a scripted recogniser.
 *
 * Only FINAL utterance text is delivered to [onUtterance]; partial text goes to
 * [onPartial] for display or diagnostics. Utterance ids come from the
 * segmenter and stay stable from start to final.
 */
class AudioUtterancePipeline(
    private val segmenter: UtteranceSegmenter,
    private val asr: AsrEngine,
    private val deduper: OcrAsrDeduper = OcrAsrDeduper(),
    private val onPartial: (utteranceId: Long, text: String) -> Unit = { _, _ -> },
    private val onUtterance: (Utterance) -> Unit,
) {
    data class Utterance(
        val utteranceId: Long,
        val text: String,
        val startMillis: Long,
        val endMillis: Long,
        /** Wall time spent in recognition after the utterance ended. */
        val recognitionMillis: Long,
    )

    private var activeId = 0L
    private var inUtterance = false

    /** Called by the screen side when on-screen text is read, so identical speech is not translated twice. */
    fun noteOcrLine(text: String, nowMillis: Long) { deduper.isDuplicate(text, fromAudio = false, nowMillis = nowMillis) }

    fun acceptFrame(frame: ShortArray, nowMillis: Long, clock: () -> Long = System::currentTimeMillis) {
        val wasActive = inUtterance
        val events = segmenter.accept(frame, nowMillis)
        val started = events.firstOrNull { it is UtteranceSegmenter.Event.Started } as? UtteranceSegmenter.Event.Started
        if (started != null) { inUtterance = true; activeId = started.utteranceId }
        if (wasActive || started != null) {
            asr.acceptFrame(frame)?.let { if (!it.isFinal && it.text.isNotBlank()) onPartial(activeId, it.text) }
        }
        val finished = events.firstOrNull { it is UtteranceSegmenter.Event.Finished } as? UtteranceSegmenter.Event.Finished
        if (finished != null) {
            inUtterance = false
            val t0 = clock()
            val text = asr.finish()
            val took = clock() - t0
            if (text.isNotBlank() && !deduper.isDuplicate(text, fromAudio = true, nowMillis = nowMillis)) {
                onUtterance(Utterance(finished.utteranceId, text, finished.startMillis, finished.endMillis, took))
            }
        } else if (inUtterance && !segmenter.isActive) {
            // The segmenter dropped a too-short burst: forget what the recogniser heard.
            inUtterance = false
            asr.reset()
        }
    }

    fun close() = asr.close()
}
