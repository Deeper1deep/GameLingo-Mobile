package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.core.audio.AsrEngine

/**
 * Ownership rules for heavy models, kept pure so they can be tested.
 */
object ModelLifecycle {
    /** android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL. */
    const val TRIM_RUNNING_CRITICAL = 15

    /**
     * Under critical memory pressure the on-device translation models are released.
     * They reload lazily on the next line, so nothing is switched off: the next line is slower.
     */
    fun shouldReleaseTranslationModels(trimLevel: Int): Boolean = trimLevel >= TRIM_RUNNING_CRITICAL

    /**
     * A model load that finished after the session was stopped must not be adopted:
     * it is closed immediately and the caller must not start capture. Returns true when adopted.
     */
    fun adoptOrClose(stillActive: Boolean, engine: AsrEngine, adopt: (AsrEngine) -> Unit): Boolean {
        if (!stillActive) {
            runCatching { engine.close() }
            return false
        }
        adopt(engine)
        return true
    }
}
