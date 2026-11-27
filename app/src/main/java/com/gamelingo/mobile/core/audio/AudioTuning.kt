package com.gamelingo.mobile.core.audio

import com.gamelingo.mobile.core.optimize.DeviceSnapshot
import com.gamelingo.mobile.core.optimize.OptimizationProfile
import com.gamelingo.mobile.core.optimize.SettingsOptimizer

/**
 * What an audio profile controls. It NEVER switches speech recognition or TTS on or off:
 * those are the user's opt-in choices. It only trades latency, sentence completeness and
 * work queued, and every direction below is a design intent, not a measured result.
 */
data class AudioTuning(
    /** Silence that closes an utterance: shorter = lower latency but more chopped sentences. */
    val hangoverMillis: Int,
    /** Longest utterance sent to the recogniser in one piece. */
    val maxUtteranceMillis: Int,
    /** Audio frames (30 ms each) waiting for the recogniser; the oldest are dropped when full. */
    val frameQueueCapacity: Int,
)

object AudioTuningPolicy {
    /** The values the app used before profiles existed. BALANCED reproduces them exactly. */
    val BALANCED = AudioTuning(hangoverMillis = 450, maxUtteranceMillis = 12_000, frameQueueCapacity = 400)

    /** Resolves Smart Auto from the real device snapshot (RAM, cores, battery, power save, thermal). */
    fun resolve(requested: OptimizationProfile, device: DeviceSnapshot): OptimizationProfile =
        SettingsOptimizer.resolve(requested, device)

    fun tuningFor(profile: OptimizationProfile): AudioTuning = when (profile) {
        OptimizationProfile.BEST_QUALITY -> AudioTuning(600, 15_000, 600)
        OptimizationProfile.PERFORMANCE -> AudioTuning(300, 8_000, 200)
        OptimizationProfile.BATTERY_SAVER -> AudioTuning(450, 8_000, 150)
        OptimizationProfile.BALANCED, OptimizationProfile.SMART_AUTO -> BALANCED
    }

    fun forRequest(requested: OptimizationProfile, device: DeviceSnapshot): AudioTuning =
        tuningFor(resolve(requested, device))
}
