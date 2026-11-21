package com.gamelingo.mobile.translate.local

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.translate.MlKitTranslationEngine
import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Picks the best verified local engine. Default (balanced) order: Marian
 * INT8 (primary, MSA + dictionary) -> ML Kit (general last-resort fallback).
 * TranslateGemma 4B HD is an ADVANCED, manually selected engine: it joins
 * the chain at the front ONLY when the user explicitly opted in
 * ([SettingsRepository] `gemmaHdOptIn`, default off) AND the device is
 * cool enough at that moment. The model being on disk is never by itself a
 * reason to load a 4B-parameter model on a thermally constrained mid-range
 * SoC. Offline mode touches the network for NOTHING: every candidate runs
 * on-device.
 *
 * A higher engine that fails mid-session degrades visibly to the next one
 * for that line only (the history entry keeps the real engine id); a
 * missing model is a skip, not an error. Circuit breaker: a DETERMINISTIC
 * failure (native [Error], init failure, declared insufficient memory)
 * poisons the engine for the rest of the session, while a transient
 * [Exception] is retried on the next line. ML Kit results get the safe
 * dictionary pass so a fallback keeps as much of the Egyptian style as the
 * terminology layer can guarantee without inventing wording.
 */
@Singleton
class LocalEngineChain internal constructor(
    private val translateGemmaEngine: LocalAiCandidate,
    private val marianEngine: LocalAiCandidate,
    private val mlKitEngine: TranslationEngine,
    private val gemmaOptIn: () -> Boolean,
    private val thermalHot: () -> Boolean,
    private val dictionaryHolder: EgyptianDictionaryHolder? = null,
) : TranslationEngine, LocalAiRouter {

    /** Hilt entry point: the real engines, live settings and the device thermal state. */
    @Inject
    constructor(
        translateGemmaEngine: TranslateGemmaEngine,
        marianEngine: LocalAiTranslationEngine,
        @Named("mlkit") mlKitEngine: TranslationEngine,
        settingsRepository: SettingsRepository,
        dictionaryHolder: EgyptianDictionaryHolder,
        @ApplicationContext context: Context,
    ) : this(
        translateGemmaEngine as LocalAiCandidate,
        marianEngine as LocalAiCandidate,
        mlKitEngine,
        gemmaOptIn = settingsRepository.gemmaOptInFlag(),
        thermalHot = { context.isThermallyConstrained() },
        dictionaryHolder = dictionaryHolder,
    )

    override val engineId: String = ENGINE_ID

    @Volatile
    private var gemmaPoisoned = false

    @Volatile
    private var marianPoisoned = false

    private fun gemmaUsable(): Boolean =
        !gemmaPoisoned && gemmaOptIn() && !thermalHot() && translateGemmaEngine.isAvailable()

    private fun marianUsable(): Boolean = !marianPoisoned && marianEngine.isAvailable()

    /** True when at least one usable local AI model is verified on disk. */
    override fun hasLocalAiModel(): Boolean = gemmaUsable() || marianUsable()

    /** Engine id the next line would actually use; the cache keys on this. */
    override fun plannedLocalEngineId(): String = when {
        gemmaUsable() -> TranslateGemmaEngine.ENGINE_ID
        marianUsable() -> LocalAiTranslationEngine.ENGINE_ID
        else -> MlKitTranslationEngine.ENGINE_ID
    }

    override suspend fun ensureReady() = Unit

    /** Failures that cannot fix themselves by the next line: poison the engine for the session. */
    private fun isDeterministic(failure: Throwable): Boolean =
        failure is Error ||
            failure is LocalAiTranslationEngine.EngineInitException ||
            failure is TranslateGemmaEngine.EngineInitException ||
            failure is TranslateGemmaEngine.InsufficientMemoryException

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        // Throwable, not Exception: native engines fail with Errors
        // (UnsatisfiedLinkError, OutOfMemoryError) that must degrade to the
        // next engine, never kill the process. Cancellation still propagates.
        if (gemmaUsable()) {
            try {
                return translateGemmaEngine.translate(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (isDeterministic(failure)) gemmaPoisoned = true
            }
        }
        if (marianUsable()) {
            try {
                return marianEngine.translate(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (isDeterministic(failure)) marianPoisoned = true
            }
        }
        return polishMlKit(request, mlKitEngine.translate(request))
    }

    /**
     * The ML Kit fallback answers in generic/MSA Arabic. Run the safe
     * dictionary pass over it so the fallback keeps as much of the Egyptian
     * style as the terminology layer guarantees, and mark the engine id
     * honestly ("+dict") when the pass changed the wording.
     */
    private fun polishMlKit(request: TranslationRequest, result: TranslationResult): TranslationResult {
        val holder = dictionaryHolder ?: return result
        val polished = runCatching {
            holder.get().toEgyptian(request.text, result.translatedText)
        }.getOrNull() ?: return result
        if (polished == result.translatedText) return result
        return result.copy(translatedText = polished, engineId = "${result.engineId}+dict")
    }

    override fun close() {
        // ML Kit is owned by its own binding and shared with the hybrid engine;
        // closing it here too would double-close the same instance.
        translateGemmaEngine.close()
        marianEngine.close()
    }

    companion object {
        const val ENGINE_ID = "local-chain"
    }
}

/**
 * Live view of the Gemma HD opt-in flag: a collector keeps a volatile copy of
 * the persisted setting so the per-line routing decision never touches
 * DataStore. Default false until the first emission arrives.
 */
private fun SettingsRepository.gemmaOptInFlag(): () -> Boolean {
    val optedIn = java.util.concurrent.atomic.AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        runCatching { settings.collect { optedIn.set(it.gemmaHdOptIn) } }
            .onFailure { scope.cancel() }
    }
    return { optedIn.get() }
}

/**
 * True when the device reports a thermal state where loading a 4B model would
 * throttle it into uselessness (SEVERE or worse). API 29+ only; below that the
 * platform cannot tell us, so the opt-in alone decides.
 */
private fun Context.isThermallyConstrained(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val powerManager = getSystemService(PowerManager::class.java) ?: return false
    return powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
}
