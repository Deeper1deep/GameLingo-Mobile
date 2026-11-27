package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.region.NormalizedRegion

/**
 * The translation area for app-window capture lives here, in fractions of the
 * captured window frame, for ONE capture session and one content size. It is a
 * separate space from the saved whole-display region, which is never reread as
 * frame fractions and never deleted. Package identity is unknown, so a new
 * capture session always starts without one and needs a fresh pick.
 */
object AppWindowRegionStore {
    data class Entry(val region: NormalizedRegion, val contentWidth: Int, val contentHeight: Int)

    @Volatile var entry: Entry? = null

    /** The region, only while the window still has the size it was picked for. */
    fun validFor(contentSize: Pair<Int, Int>?): NormalizedRegion? {
        val e = entry ?: return null
        val s = contentSize ?: return null
        return e.region.takeIf { e.contentWidth == s.first && e.contentHeight == s.second }
    }

    fun clear() { entry = null }
}

/** Which region the capture loop may use. */
object AppWindowRegionPolicy {
    sealed interface Decision {
        /** Whole-screen capture, or custom area off: unchanged behaviour. */
        data object UseSettings : Decision
        data class UseSession(val region: NormalizedRegion) : Decision
        /** App-window capture with a custom area on but no valid pick: OCR must pause. */
        data object NeedsRepick : Decision
    }

    fun decide(appWindowActive: Boolean, customRegionEnabled: Boolean, contentSize: Pair<Int, Int>?): Decision {
        if (!appWindowActive) return Decision.UseSettings
        AppWindowRegionStore.validFor(contentSize)?.let { return Decision.UseSession(it) }
        // A pick that no longer fits the window, or a custom area the user
        // turned on, both need a fresh pick; nothing falls back to the display region.
        return if (customRegionEnabled || AppWindowRegionStore.entry != null) Decision.NeedsRepick else Decision.UseSettings
    }
}
