package com.gamelingo.mobile.core.optimize

import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsConstraints
import kotlinx.serialization.Serializable

/** The profiles the "best settings" feature can apply. */
enum class OptimizationProfile { SMART_AUTO, BEST_QUALITY, BALANCED, PERFORMANCE, BATTERY_SAVER }

/** What the analyzer measured on this device right now. All values are real readings. */
data class DeviceSnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val lowRamDevice: Boolean,
    /** 0..100, or -1 when unknown. */
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSaveMode: Boolean,
    /** Android thermal status: 0 none, 1 light, 2 moderate, 3+ severe; 0 when unknown. */
    val thermalStatus: Int,
    val marianInstalled: Boolean,
    val gemmaInstalled: Boolean,
)

/**
 * The performance-related settings a profile controls. Deliberately excludes
 * privacy and identity settings (AI mode, API key, cloud opt-in, Arabic mode,
 * overlay look): optimizing speed must never send text off-device or restyle
 * the subtitles.
 */
@Serializable
data class TuningValues(
    val captureIntervalMs: Long,
    val batterySaverEnabled: Boolean,
    val dedupEnabled: Boolean,
    val ignoreUnstableText: Boolean,
    val smartTextDetection: Boolean,
    val cacheEnabled: Boolean,
    val contextLines: Int,
    val gemmaHdOptIn: Boolean,
    val requestTimeoutSec: Int,
) {
    companion object {
        fun of(s: AppSettings) = TuningValues(
            captureIntervalMs = s.captureIntervalMs,
            batterySaverEnabled = s.batterySaverEnabled,
            dedupEnabled = s.dedupEnabled,
            ignoreUnstableText = s.ignoreUnstableText,
            smartTextDetection = s.smartTextDetection,
            cacheEnabled = s.cacheEnabled,
            contextLines = s.contextLines,
            gemmaHdOptIn = s.gemmaHdOptIn,
            requestTimeoutSec = s.requestTimeoutSec,
        )
    }
}

enum class TuningField {
    CAPTURE_INTERVAL, BATTERY_SAVER, DEDUP, IGNORE_UNSTABLE, SMART_DETECTION,
    CACHE, CONTEXT_LINES, GEMMA_HD, TIMEOUT,
}

/** One real before -> after difference shown in the report. */
data class TuningChange(val field: TuningField, val before: String, val after: String)

/** Result of an optimization: which profile really applied and what changed. */
data class OptimizationReport(
    val requested: OptimizationProfile,
    val applied: OptimizationProfile,
    val changes: List<TuningChange>,
)

/** Pure decision logic: no Android, fully unit-testable. */
object SettingsOptimizer {

    // Hysteresis: the exit thresholds differ from the entry thresholds, so a
    // reading hovering around one limit does not flip the profile each run.
    const val BATTERY_ENTER_PERCENT = 15
    const val BATTERY_EXIT_PERCENT = 30
    const val THERMAL_ENTER = 2
    const val THERMAL_EXIT = 1
    const val LOW_RAM_MB = 4_096L
    const val HIGH_RAM_MB = 8_000L
    const val HIGH_CORES = 8

    /**
     * Smart Auto: picks a concrete profile from the snapshot. [previous] is
     * the profile Smart Auto chose last time; it is kept until the condition
     * that caused it has clearly cleared (hysteresis).
     */
    fun chooseAuto(device: DeviceSnapshot, previous: OptimizationProfile? = null): OptimizationProfile {
        val battery = device.batteryPercent
        val lowBatteryNow = battery in 0..BATTERY_ENTER_PERCENT && !device.charging
        val lowBatteryStill = previous == OptimizationProfile.BATTERY_SAVER &&
            battery in 0 until BATTERY_EXIT_PERCENT && !device.charging
        if (device.powerSaveMode || lowBatteryNow || lowBatteryStill) {
            return OptimizationProfile.BATTERY_SAVER
        }
        val hotNow = device.thermalStatus >= THERMAL_ENTER
        val hotStill = previous == OptimizationProfile.PERFORMANCE && device.thermalStatus >= THERMAL_EXIT
        if (hotNow || hotStill) return OptimizationProfile.PERFORMANCE
        if (device.lowRamDevice || device.totalRamMb < LOW_RAM_MB) return OptimizationProfile.PERFORMANCE
        return if (device.totalRamMb >= HIGH_RAM_MB && device.cpuCores >= HIGH_CORES) {
            OptimizationProfile.BEST_QUALITY
        } else {
            OptimizationProfile.BALANCED
        }
    }

    /** Resolves [requested] (Smart Auto included) to the profile that really applies. */
    fun resolve(
        requested: OptimizationProfile,
        device: DeviceSnapshot,
        previousAuto: OptimizationProfile? = null,
    ): OptimizationProfile =
        if (requested == OptimizationProfile.SMART_AUTO) chooseAuto(device, previousAuto) else requested

    /** Concrete values for a non-auto [profile] on this [device]. */
    fun tuningFor(profile: OptimizationProfile, device: DeviceSnapshot): TuningValues = when (profile) {
        OptimizationProfile.BEST_QUALITY -> TuningValues(
            captureIntervalMs = 450,
            batterySaverEnabled = false,
            dedupEnabled = true,
            ignoreUnstableText = true,
            smartTextDetection = true,
            cacheEnabled = true,
            contextLines = 8,
            // HD local model only where it can actually run well; the engine
            // keeps its own live memory/thermal gate on top of this.
            gemmaHdOptIn = device.gemmaInstalled && device.totalRamMb >= HIGH_RAM_MB &&
                !device.powerSaveMode && device.thermalStatus <= THERMAL_EXIT,
            requestTimeoutSec = 30,
        )
        OptimizationProfile.PERFORMANCE -> TuningValues(
            captureIntervalMs = 900,
            batterySaverEnabled = true,
            dedupEnabled = true,
            ignoreUnstableText = true,
            smartTextDetection = true,
            cacheEnabled = true,
            contextLines = 3,
            gemmaHdOptIn = false,
            requestTimeoutSec = 15,
        )
        OptimizationProfile.BATTERY_SAVER -> TuningValues(
            captureIntervalMs = 1_500,
            batterySaverEnabled = true,
            dedupEnabled = true,
            ignoreUnstableText = true,
            smartTextDetection = true,
            cacheEnabled = true,
            contextLines = 2,
            gemmaHdOptIn = false,
            requestTimeoutSec = 15,
        )
        OptimizationProfile.BALANCED, OptimizationProfile.SMART_AUTO -> TuningValues(
            captureIntervalMs = SettingsConstraints.DEFAULT_CAPTURE_INTERVAL_MS,
            batterySaverEnabled = true,
            dedupEnabled = true,
            ignoreUnstableText = true,
            smartTextDetection = true,
            cacheEnabled = true,
            contextLines = SettingsConstraints.DEFAULT_CONTEXT_LINES,
            gemmaHdOptIn = false,
            requestTimeoutSec = SettingsConstraints.DEFAULT_TIMEOUT_SEC,
        )
    }

    /** Only the fields that really differ, with their real before/after values. */
    fun diff(before: TuningValues, after: TuningValues): List<TuningChange> = buildList {
        fun add(field: TuningField, b: Any, a: Any) {
            if (b != a) add(TuningChange(field, b.toString(), a.toString()))
        }
        add(TuningField.CAPTURE_INTERVAL, before.captureIntervalMs, after.captureIntervalMs)
        add(TuningField.BATTERY_SAVER, before.batterySaverEnabled, after.batterySaverEnabled)
        add(TuningField.DEDUP, before.dedupEnabled, after.dedupEnabled)
        add(TuningField.IGNORE_UNSTABLE, before.ignoreUnstableText, after.ignoreUnstableText)
        add(TuningField.SMART_DETECTION, before.smartTextDetection, after.smartTextDetection)
        add(TuningField.CACHE, before.cacheEnabled, after.cacheEnabled)
        add(TuningField.CONTEXT_LINES, before.contextLines, after.contextLines)
        add(TuningField.GEMMA_HD, before.gemmaHdOptIn, after.gemmaHdOptIn)
        add(TuningField.TIMEOUT, before.requestTimeoutSec, after.requestTimeoutSec)
    }

    fun plan(
        requested: OptimizationProfile,
        device: DeviceSnapshot,
        current: TuningValues,
        previousAuto: OptimizationProfile? = null,
    ): Pair<TuningValues, OptimizationReport> {
        val applied = resolve(requested, device, previousAuto)
        val target = tuningFor(applied, device)
        return target to OptimizationReport(requested, applied, diff(current, target))
    }
}
