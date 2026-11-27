package com.gamelingo.mobile.core.audio

import com.gamelingo.mobile.core.optimize.DeviceSnapshot
import com.gamelingo.mobile.core.optimize.OptimizationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTuningTest {
    private fun device(ram: Long = 8000, cores: Int = 8, battery: Int = 80, saver: Boolean = false, thermal: Int = 0, low: Boolean = false) =
        DeviceSnapshot(ram, ram / 2, cores, low, battery, false, saver, thermal, marianInstalled = true, gemmaInstalled = false)

    @Test fun `balanced reproduces the pre-profile constants exactly`() {
        assertEquals(AudioTuning(450, 12_000, 400), AudioTuningPolicy.tuningFor(OptimizationProfile.BALANCED))
    }

    @Test fun `low latency closes utterances sooner and queues less than best quality`() {
        val q = AudioTuningPolicy.tuningFor(OptimizationProfile.BEST_QUALITY)
        val l = AudioTuningPolicy.tuningFor(OptimizationProfile.PERFORMANCE)
        assertTrue(l.hangoverMillis < q.hangoverMillis)
        assertTrue(l.frameQueueCapacity < q.frameQueueCapacity)
        assertTrue(l.maxUtteranceMillis < q.maxUtteranceMillis)
    }

    @Test fun `battery saver bounds work with the smallest queue`() {
        val b = AudioTuningPolicy.tuningFor(OptimizationProfile.BATTERY_SAVER)
        assertEquals(150, b.frameQueueCapacity)
        assertTrue(b.maxUtteranceMillis <= 8_000)
    }

    @Test fun `smart auto follows the device snapshot`() {
        assertEquals(OptimizationProfile.BATTERY_SAVER, AudioTuningPolicy.resolve(OptimizationProfile.SMART_AUTO, device(saver = true)))
        assertEquals(OptimizationProfile.PERFORMANCE, AudioTuningPolicy.resolve(OptimizationProfile.SMART_AUTO, device(thermal = 3)))
        assertEquals(OptimizationProfile.BEST_QUALITY, AudioTuningPolicy.resolve(OptimizationProfile.SMART_AUTO, device()))
        assertEquals(AudioTuningPolicy.tuningFor(OptimizationProfile.PERFORMANCE), AudioTuningPolicy.forRequest(OptimizationProfile.SMART_AUTO, device(low = true)))
    }

    @Test fun `an explicit choice is never overridden by the device`() {
        assertEquals(OptimizationProfile.BEST_QUALITY, AudioTuningPolicy.resolve(OptimizationProfile.BEST_QUALITY, device(saver = true)))
    }

    @Test fun `no tuning field can turn speech or tts on or off`() {
        val names = AudioTuning::class.java.declaredFields.map { it.name }
        assertTrue(names.none { it.contains("speech", true) || it.contains("tts", true) || it.contains("enabled", true) })
    }
}
