package com.gamelingo.mobile.core.optimize

import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOptimizerTest {

    private fun device(
        ram: Long = 6_000, cores: Int = 8, lowRam: Boolean = false, battery: Int = 80,
        charging: Boolean = false, powerSave: Boolean = false, thermal: Int = 0,
        gemma: Boolean = false,
    ) = DeviceSnapshot(ram, ram / 2, cores, lowRam, battery, charging, powerSave, thermal, true, gemma)

    @Test
    fun `mid device gets balanced`() =
        assertEquals(OptimizationProfile.BALANCED, SettingsOptimizer.chooseAuto(device()))

    @Test
    fun `strong device gets best quality`() =
        assertEquals(OptimizationProfile.BEST_QUALITY, SettingsOptimizer.chooseAuto(device(ram = 12_000)))

    @Test
    fun `low ram device gets performance`() {
        assertEquals(OptimizationProfile.PERFORMANCE, SettingsOptimizer.chooseAuto(device(ram = 3_000)))
        assertEquals(OptimizationProfile.PERFORMANCE, SettingsOptimizer.chooseAuto(device(lowRam = true)))
    }

    @Test
    fun `power save or low battery gets battery saver`() {
        assertEquals(OptimizationProfile.BATTERY_SAVER, SettingsOptimizer.chooseAuto(device(powerSave = true)))
        assertEquals(OptimizationProfile.BATTERY_SAVER, SettingsOptimizer.chooseAuto(device(battery = 10)))
    }

    @Test
    fun `low battery while charging is not battery saver`() =
        assertFalse(SettingsOptimizer.chooseAuto(device(battery = 10, charging = true)) == OptimizationProfile.BATTERY_SAVER)

    @Test
    fun `battery saver sticks until the battery clearly recovers`() {
        val prev = OptimizationProfile.BATTERY_SAVER
        assertEquals(prev, SettingsOptimizer.chooseAuto(device(battery = 22), prev))
        assertEquals(OptimizationProfile.BALANCED, SettingsOptimizer.chooseAuto(device(battery = 35), prev))
        // Without history the same 22% reading is not low enough to enter.
        assertEquals(OptimizationProfile.BALANCED, SettingsOptimizer.chooseAuto(device(battery = 22), null))
    }

    @Test
    fun `heat gets performance with hysteresis`() {
        assertEquals(OptimizationProfile.PERFORMANCE, SettingsOptimizer.chooseAuto(device(thermal = 2)))
        assertEquals(OptimizationProfile.BALANCED, SettingsOptimizer.chooseAuto(device(thermal = 1), null))
        assertEquals(
            OptimizationProfile.PERFORMANCE,
            SettingsOptimizer.chooseAuto(device(thermal = 1), OptimizationProfile.PERFORMANCE),
        )
        assertEquals(
            OptimizationProfile.BALANCED,
            SettingsOptimizer.chooseAuto(device(thermal = 0), OptimizationProfile.PERFORMANCE),
        )
    }

    @Test
    fun `hd model is only enabled on a strong cool device that has it installed`() {
        val strong = device(ram = 12_000, gemma = true)
        assertTrue(SettingsOptimizer.tuningFor(OptimizationProfile.BEST_QUALITY, strong).gemmaHdOptIn)
        assertFalse(SettingsOptimizer.tuningFor(OptimizationProfile.BEST_QUALITY, strong.copy(gemmaInstalled = false)).gemmaHdOptIn)
        assertFalse(SettingsOptimizer.tuningFor(OptimizationProfile.BEST_QUALITY, strong.copy(thermalStatus = 2)).gemmaHdOptIn)
        assertFalse(SettingsOptimizer.tuningFor(OptimizationProfile.BEST_QUALITY, device(ram = 6_000, gemma = true)).gemmaHdOptIn)
        OptimizationProfile.values().filter { it != OptimizationProfile.BEST_QUALITY }.forEach {
            assertFalse(SettingsOptimizer.tuningFor(it, strong).gemmaHdOptIn)
        }
    }

    @Test
    fun `profiles are ordered by scan cost and stay inside the setting limits`() {
        val d = device()
        val q = SettingsOptimizer.tuningFor(OptimizationProfile.BEST_QUALITY, d)
        val b = SettingsOptimizer.tuningFor(OptimizationProfile.BALANCED, d)
        val p = SettingsOptimizer.tuningFor(OptimizationProfile.PERFORMANCE, d)
        val s = SettingsOptimizer.tuningFor(OptimizationProfile.BATTERY_SAVER, d)
        assertTrue(q.captureIntervalMs < b.captureIntervalMs)
        assertTrue(b.captureIntervalMs < p.captureIntervalMs)
        assertTrue(p.captureIntervalMs < s.captureIntervalMs)
        listOf(q, b, p, s).forEach {
            assertEquals(it.captureIntervalMs, SettingsConstraints.clampCaptureIntervalMs(it.captureIntervalMs))
            assertEquals(it.contextLines, SettingsConstraints.clampContextLines(it.contextLines))
            assertEquals(it.requestTimeoutSec, SettingsConstraints.clampTimeoutSec(it.requestTimeoutSec))
        }
    }

    @Test
    fun `report lists only the real differences`() {
        val current = TuningValues.of(AppSettings())
        val (_, report) = SettingsOptimizer.plan(OptimizationProfile.BALANCED, device(), current)
        assertTrue(report.changes.isEmpty())
        val (_, saver) = SettingsOptimizer.plan(OptimizationProfile.BATTERY_SAVER, device(), current)
        val interval = saver.changes.single { it.field == TuningField.CAPTURE_INTERVAL }
        assertEquals("600", interval.before)
        assertEquals("1500", interval.after)
        assertTrue(saver.changes.none { it.before == it.after })
    }

    @Test
    fun `smart auto reports the profile it really applied`() {
        val (_, report) = SettingsOptimizer.plan(
            OptimizationProfile.SMART_AUTO, device(powerSave = true), TuningValues.of(AppSettings()),
        )
        assertEquals(OptimizationProfile.SMART_AUTO, report.requested)
        assertEquals(OptimizationProfile.BATTERY_SAVER, report.applied)
    }
}
