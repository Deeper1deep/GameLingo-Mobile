package com.gamelingo.mobile.data.optimize

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.gamelingo.mobile.core.optimize.DeviceSnapshot
import com.gamelingo.mobile.core.optimize.OptimizationProfile
import com.gamelingo.mobile.core.optimize.OptimizationReport
import com.gamelingo.mobile.core.optimize.SettingsOptimizer
import com.gamelingo.mobile.core.optimize.TuningValues
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.translate.local.LocalModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface OptimizerState {
    data object Idle : OptimizerState
    data object Analyzing : OptimizerState
    data class Applied(val report: OptimizationReport, val device: DeviceSnapshot) : OptimizerState
    data object Restored : OptimizerState
}

/**
 * "Optimize My App": measures the device, picks a profile with the pure
 * [SettingsOptimizer], applies it through the normal settings setters and
 * remembers the user's previous values so they can be restored in one tap.
 * Touches performance settings only (see [TuningValues]).
 */
@Singleton
class OptimizationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val modelManager: LocalModelManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<OptimizerState>(OptimizerState.Idle)
    val state: StateFlow<OptimizerState> = _state.asStateFlow()

    private val _hasBackup = MutableStateFlow(prefs.contains(KEY_BACKUP))
    val hasBackup: StateFlow<Boolean> = _hasBackup.asStateFlow()

    /** Real readings from this device, taken now. */
    suspend fun analyze(): DeviceSnapshot = withContext(Dispatchers.Default) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        DeviceSnapshot(
            totalRamMb = mem.totalMem / MB,
            availableRamMb = mem.availMem / MB,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            lowRamDevice = am.isLowRamDevice,
            batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let { if (it in 0..100) it else -1 },
            charging = charging,
            powerSaveMode = pm.isPowerSaveMode,
            thermalStatus = thermal.coerceAtLeast(0),
            marianInstalled = modelManager.isInstalled(LocalModelManager.MODEL_MARIAN),
            gemmaInstalled = modelManager.isInstalled(LocalModelManager.MODEL_TRANSLATEGEMMA),
        )
    }

    /** Analyze, apply and report. The pre-optimization values are kept once, for [restore]. */
    suspend fun optimize(requested: OptimizationProfile): OptimizationReport = mutex.withLock {
        _state.value = OptimizerState.Analyzing
        try {
            val device = analyze()
            val current = TuningValues.of(repository.settings.first())
            val previousAuto = prefs.getString(KEY_LAST_AUTO, null)
                ?.let { runCatching { OptimizationProfile.valueOf(it) }.getOrNull() }
            val (target, report) = SettingsOptimizer.plan(requested, device, current, previousAuto)
            // Keep the ORIGINAL values: re-optimizing must not overwrite them
            // with an earlier optimization's output.
            if (!prefs.contains(KEY_BACKUP)) {
                prefs.edit().putString(KEY_BACKUP, json.encodeToString(current)).apply()
                _hasBackup.value = true
            }
            if (requested == OptimizationProfile.SMART_AUTO) {
                prefs.edit().putString(KEY_LAST_AUTO, report.applied.name).apply()
            }
            apply(target)
            _state.value = OptimizerState.Applied(report, device)
            report
        } catch (failure: Throwable) {
            _state.value = OptimizerState.Idle
            throw failure
        }
    }

    /** Puts the settings back exactly as they were before the first optimization. */
    suspend fun restore(): Boolean = mutex.withLock {
        val saved = prefs.getString(KEY_BACKUP, null)
            ?.let { runCatching { json.decodeFromString<TuningValues>(it) }.getOrNull() }
        if (saved == null) {
            prefs.edit().remove(KEY_BACKUP).apply()
            _hasBackup.value = false
            return false
        }
        apply(saved)
        prefs.edit().remove(KEY_BACKUP).remove(KEY_LAST_AUTO).apply()
        _hasBackup.value = false
        _state.value = OptimizerState.Restored
        true
    }

    private suspend fun apply(t: TuningValues) {
        repository.setCaptureIntervalMs(t.captureIntervalMs)
        repository.setBatterySaverEnabled(t.batterySaverEnabled)
        repository.setDedupEnabled(t.dedupEnabled)
        repository.setIgnoreUnstableText(t.ignoreUnstableText)
        repository.setSmartTextDetection(t.smartTextDetection)
        repository.setCacheEnabled(t.cacheEnabled)
        repository.setContextLines(t.contextLines)
        repository.setGemmaHdOptIn(t.gemmaHdOptIn)
        repository.setRequestTimeoutSec(t.requestTimeoutSec)
    }

    private companion object {
        const val PREFS = "gamelingo_optimizer"
        const val KEY_BACKUP = "tuning_backup"
        const val KEY_LAST_AUTO = "last_auto_profile"
        const val MB = 1024L * 1024L
    }
}
