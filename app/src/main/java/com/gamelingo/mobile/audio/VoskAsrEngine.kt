package com.gamelingo.mobile.audio

import com.gamelingo.mobile.core.audio.AsrEngine
import com.gamelingo.mobile.core.audio.AsrJson
import com.gamelingo.mobile.core.audio.AsrUpdate
import java.io.File
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline English recogniser (Vosk, Apache 2.0) over a model directory the
 * user downloaded. Not thread-safe: use from one worker.
 */
class VoskAsrEngine(modelDir: File, sampleRate: Float = 16_000f) : AsrEngine {
    private var model: Model? = Model(modelDir.absolutePath)
    private var recognizer: Recognizer? = Recognizer(model, sampleRate)
    private var lastPartial = ""

    override fun acceptFrame(pcm: ShortArray): AsrUpdate? {
        val r = recognizer ?: return null
        val sentenceDone = r.acceptWaveForm(pcm, pcm.size)
        if (sentenceDone) {
            // Vosk's own endpointer closed a sentence inside a long utterance.
            val text = AsrJson.textOf(r.result)
            lastPartial = ""
            if (text.isBlank()) return null
            sentences.append(text).append(' ')
            return AsrUpdate(sentences.toString().trim(), isFinal = false)
        }
        val partial = AsrJson.textOf(r.partialResult)
        if (partial == lastPartial) return null
        lastPartial = partial
        return AsrUpdate(partial, isFinal = false)
    }

    private val sentences = StringBuilder()

    override fun finish(): String {
        val r = recognizer ?: return ""
        val tail = AsrJson.textOf(r.finalResult)
        val all = (sentences.toString() + " " + tail).trim()
        sentences.clear()
        lastPartial = ""
        r.reset()
        return all
    }

    override fun reset() {
        sentences.clear(); lastPartial = ""
        runCatching { recognizer?.reset() }
    }

    override fun close() {
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
    }
}
