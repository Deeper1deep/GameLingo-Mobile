package com.gamelingo.mobile.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures what other apps play (games, media) through the screen-capture
 * MediaProjection. Android only delivers audio from apps that allow capture
 * and use USAGE_GAME, USAGE_MEDIA or USAGE_UNKNOWN; anything else is silent
 * here. Needs the RECORD_AUDIO permission and Android 10+.
 * Frames are 16 kHz mono PCM16, [FRAME_SAMPLES] samples (30 ms) each.
 */
class PlaybackAudioCapture(private val projection: MediaProjection) {

    private var record: AudioRecord? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO before start().
    fun start(scope: CoroutineScope, onFrame: (ShortArray, Long) -> Unit, onFailure: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { onFailure("android_10_required"); return }
        if (job != null) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_SAMPLES * 2 * 8))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "audio capture could not be created", e)
            onFailure("capture_create_failed"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onFailure("capture_not_initialized"); return }
        record = rec
        job = scope.launch(Dispatchers.IO) {
            try {
                rec.startRecording()
                val clockStart = System.currentTimeMillis()
                var frames = 0L
                val buf = ShortArray(FRAME_SAMPLES)
                while (isActive) {
                    var got = 0
                    while (got < FRAME_SAMPLES && isActive) {
                        val n = rec.read(buf, got, FRAME_SAMPLES - got)
                        if (n < 0) { onFailure("read_error_$n"); return@launch }
                        got += n
                    }
                    if (got == FRAME_SAMPLES) {
                        onFrame(buf.copyOf(), clockStart + frames * FRAME_MILLIS)
                        frames++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio capture loop failed", e)
                onFailure("capture_loop_failed")
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MILLIS = 30
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MILLIS / 1000
        private const val TAG = "PlaybackAudioCapture"
    }
}
