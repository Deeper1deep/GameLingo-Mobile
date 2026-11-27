package com.gamelingo.mobile.core.diagnostics

/**
 * Bounded in-memory trace of translation outcomes. METADATA ONLY: stage,
 * engine id, text LENGTHS, issue names, retry/reject flags and timings.
 * It never stores source or translated text, never touches disk and is
 * never uploaded. Lost when the process ends.
 */
object PipelineTrace {

    data class Event(
        val atMillis: Long,
        val subtitleId: Long,
        val outcome: String,
        val engineId: String,
        val sourceChars: Int,
        val outputChars: Int,
        val issues: List<String>,
        val retried: Boolean,
        val fromCache: Boolean,
        val engineMillis: Long,
    )

    /**
     * OCR stage, METADATA ONLY: sizes, counts and timings, never text, token ids or pixels.
     * [meanConfidence] is null when the recogniser reported none (shown as "unavailable", never as 0).
     */
    data class OcrEvent(
        val atMillis: Long,
        val frameW: Int,
        val frameH: Int,
        val cropW: Int,
        val cropH: Int,
        /** Active custom region in frame pixels as "l,t,r,b", or null when none. */
        val regionPx: String?,
        val blocks: Int,
        val meanConfidence: Float?,
        val rawChars: Int,
        val normalizedChars: Int,
        val preprocess: String,
        val ocrMillis: Long,
    )

    const val CAPACITY = 200
    const val OCR_CAPACITY = 60

    private val ocrEvents = ArrayDeque<OcrEvent>()

    fun recordOcr(event: OcrEvent) = synchronized(ocrEvents) {
        ocrEvents.addLast(event)
        while (ocrEvents.size > OCR_CAPACITY) ocrEvents.removeFirst()
    }

    fun ocrSnapshot(): List<OcrEvent> = synchronized(ocrEvents) { ocrEvents.toList() }

    private val events = ArrayDeque<Event>()

    fun record(event: Event) = synchronized(events) {
        events.addLast(event)
        while (events.size > CAPACITY) events.removeFirst()
    }

    fun snapshot(): List<Event> = synchronized(events) { events.toList() }

    fun clear() {
        synchronized(events) { events.clear() }
        synchronized(ocrEvents) { ocrEvents.clear() }
    }

    /** Plain-text summary for sharing. Contains no subtitle content. */
    fun export(): String = buildString {
        appendLine("GameLingo pipeline trace (metadata only, no subtitle text)")
        appendLine("not recorded: token ids, attention masks, model-internal tensors, raw OCR text (opt-in not implemented)")
        for (o in ocrSnapshot()) {
            appendLine(
                "t=${o.atMillis} ocr frame=${o.frameW}x${o.frameH} crop=${o.cropW}x${o.cropH} " +
                    "region=${o.regionPx ?: "none"} blocks=${o.blocks} " +
                    "conf=${o.meanConfidence?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "unavailable"} " +
                    "raw=${o.rawChars}c norm=${o.normalizedChars}c pre=${o.preprocess} ms=${o.ocrMillis}",
            )
        }
        for (e in snapshot()) {
            appendLine(
                "t=${e.atMillis} id=${e.subtitleId} ${e.outcome} engine=${e.engineId} " +
                    "src=${e.sourceChars}c out=${e.outputChars}c ms=${e.engineMillis} " +
                    "retry=${e.retried} cache=${e.fromCache} issues=${e.issues.joinToString(",")}",
            )
        }
    }
}
