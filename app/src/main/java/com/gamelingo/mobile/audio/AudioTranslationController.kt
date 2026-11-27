package com.gamelingo.mobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import com.gamelingo.mobile.core.audio.AudioUtterancePipeline
import com.gamelingo.mobile.core.audio.UtteranceSegmenter
import com.gamelingo.mobile.core.diagnostics.PipelineTrace
import com.gamelingo.mobile.core.session.TranslationSessionBus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs speech translation (capture, speech detection, recognition) and the
 * optional spoken output for one capture session. Both are opt-in and do
 * nothing unless the user turned them on; speech also needs the downloaded
 * model and the RECORD_AUDIO permission.
 */
@Singleton
class AudioTranslationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AudioPrefs,
    private val models: AsrModelManager,
    private val optimizer: com.gamelingo.mobile.data.optimize.OptimizationManager,
) {
    enum class Status { OFF, NO_MODEL, NO_PERMISSION, LOADING, LISTENING, FAILED }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var capture: PlaybackAudioCapture? = null
    @Volatile private var pipeline: AudioUtterancePipeline? = null
    private var tts: TtsSpeaker? = null
    private val jobs = mutableListOf<Job>()

    @Synchronized
    fun start(projection: MediaProjection, scope: CoroutineScope) {
        stop()
        val settings = prefs.settings.value
        if (settings.ttsEnabled) startTts(scope)
        if (!settings.speechEnabled) { _status.value = Status.OFF; return }
        val modelDir = models.modelDir()
        if (modelDir == null) { _status.value = Status.NO_MODEL; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.value = Status.NO_PERMISSION; return
        }
        _status.value = Status.LOADING
        com.gamelingo.mobile.core.audio.SourceActivity.smartAuto =
            settings.profile == com.gamelingo.mobile.core.optimize.OptimizationProfile.SMART_AUTO
        jobs += scope.launch(Dispatchers.Default) {
            // Real device readings for Smart Auto; an explicit profile ignores them.
            val tuning = com.gamelingo.mobile.core.audio.AudioTuningPolicy.forRequest(settings.profile, optimizer.analyze())
            val frames = Channel<Pair<ShortArray, Long>>(capacity = tuning.frameQueueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val asr = try { VoskAsrEngine(modelDir) } catch (e: Throwable) {
                Log.w(TAG, "speech model failed to load", e)
                _status.value = Status.FAILED; return@launch
            }
            // stop() may have run while the blocking model load was in progress: do not adopt a
            // stale model or start capture for a session that has already ended.
            var adoptedAsr: com.gamelingo.mobile.core.audio.AsrEngine? = null
            if (!com.gamelingo.mobile.core.engine.ModelLifecycle.adoptOrClose(isActive, asr) { adoptedAsr = it }) return@launch
            val p = AudioUtterancePipeline(
                segmenter = UtteranceSegmenter(
                    hangoverMillis = tuning.hangoverMillis,
                    maxUtteranceMillis = tuning.maxUtteranceMillis,
                ),
                asr = adoptedAsr!!,
                onUtterance = { u ->
                    com.gamelingo.mobile.core.audio.SourceActivity.lastAudioAtMillis = System.currentTimeMillis()
                    val id = TranslationSessionBus.onAudioLine(u.text, System.currentTimeMillis(), u.utteranceId)
                    PipelineTrace.record(
                        PipelineTrace.Event(
                            System.currentTimeMillis(), id, "asr", "vosk-small-en-us-0.15",
                            0, u.text.length, emptyList(), false, false, u.recognitionMillis,
                        ),
                    )
                    scope.launch { delay(displayMillis(u.text)); TranslationSessionBus.clearAudioLine(id) }
                },
            )
            pipeline = p
            val cap = PlaybackAudioCapture(projection)
            capture = cap
            cap.start(
                scope,
                onFrame = { f, t -> frames.trySend(f to t) },
                onFailure = { Log.w(TAG, "audio capture stopped: $it"); _status.value = Status.FAILED },
            )
            if (_status.value == Status.LOADING) _status.value = Status.LISTENING
            try {
                for ((f, t) in frames) p.acceptFrame(f, t)
            } finally { p.close() }
        }
        jobs += scope.launch {
            TranslationSessionBus.latestLine.collect { line ->
                if (line != null) pipeline?.noteOcrLine(line.text, line.capturedAtMillis)
            }
        }
    }

    private fun startTts(scope: CoroutineScope) {
        val speaker = TtsSpeaker(context).also { tts = it }
        jobs += scope.launch {
            var last: Pair<String, Long>? = null
            TranslationSessionBus.translatedLine.collect { line ->
                if (line == null) { speaker.stopSpeaking(); return@collect }
                val key = line.original to line.capturedAtMillis
                if (key != last) { last = key; speaker.speak(line.translated, "gl-${line.subtitleId}") }
            }
        }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        capture?.stop(); capture = null
        pipeline = null
        tts?.shutdown(); tts = null
        com.gamelingo.mobile.core.audio.SourceActivity.reset()
        _status.value = Status.OFF
    }

    private fun displayMillis(text: String): Long = (1_500L + text.length * 70L).coerceIn(2_500L, 9_000L)

    private companion object { const val TAG = "AudioTranslation" }
}
