package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.RecognizedBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Link between the region selector window and the capture service for the
 * live preview. The selector publishes the rectangle being edited; while it
 * is set, the capture service only runs OCR for the preview and publishes
 * which text would be accepted. Nothing is translated in this mode.
 */
object RegionPreviewBus {

    data class Candidate(val region: NormalizedRegion, val sensitivity: RegionSensitivity)

    /** One recognized text box as screen fractions, with the filter's verdict. */
    data class Box(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val accepted: Boolean,
    )

    data class Result(val boxes: List<Box>)

    private val _candidate = MutableStateFlow<Candidate?>(null)
    val candidate: StateFlow<Candidate?> = _candidate.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    fun setCandidate(candidate: Candidate?) {
        _candidate.value = candidate
        if (candidate == null) _result.value = null
    }

    fun publish(result: Result) {
        // Only meaningful while a selector is open.
        if (_candidate.value != null) _result.value = result
    }

    /** Pure: the accepted/rejected boxes of [blocks] for [candidate] in a frame of the given size. */
    fun build(
        blocks: List<RecognizedBlock>,
        frameWidth: Int,
        frameHeight: Int,
        candidate: Candidate,
        transform: DisplayToCaptureTransform? = null,
    ): Result {
        if (frameWidth <= 0 || frameHeight <= 0) return Result(emptyList())
        // Same mapping OCR uses: boxes are drawn in DISPLAY fractions, the
        // region is judged in frame pixels through the explicit transform.
        val t = transform ?: DisplayToCaptureTransform(frameWidth, frameHeight, frameWidth, frameHeight)
        val px = t.toCapturePixels(candidate.region) ?: return Result(emptyList())
        val boxes = blocks.mapNotNull { block ->
            val r = block.rect
            val f = t.frameRectToDisplayFractions(r) ?: return@mapNotNull null
            Box(f[0], f[1], f[2], f[3], accepted = RegionFilter.accepts(r, px, candidate.sensitivity))
        }
        return Result(boxes)
    }
}
