package com.gamelingo.mobile.core.session

/**
 * Process-local marker for "the system killed the capture session under the
 * user" (MediaProjection revoked or superseded - a real failure mode on
 * Android 14+ where a projection can end at any time). The capture service
 * sets it; the home screen reads it to offer a one-tap resume, and a resume
 * notification tap additionally asks the home screen to launch the consent
 * flow immediately. Cleared as soon as a new session starts.
 */
object ResumeRequest {

    /** Launch intent extra that marks a tap on the resume notification. */
    const val EXTRA_AUTO_RESUME = "com.gamelingo.mobile.extra.AUTO_RESUME"

    @Volatile var interrupted: Boolean = false
        private set

    @Volatile private var autoResume: Boolean = false

    /** Called by the capture service when the projection dies unexpectedly. */
    fun onUnexpectedInterruption() {
        interrupted = true
    }

    /** Called by MainActivity when launched from the resume notification. */
    fun onResumeNotificationTap() {
        autoResume = true
    }

    /** One-shot read for the home screen: true exactly once per tap. */
    fun consumeAutoResume(): Boolean {
        val requested = autoResume
        autoResume = false
        return requested
    }

    /** A new session is starting; every marker is stale now. */
    fun onSessionStarting() {
        interrupted = false
        autoResume = false
    }

    /** The user dismissed the resume offer. */
    fun dismiss() {
        interrupted = false
    }

    /** Test hook: back to a pristine state. */
    fun reset() {
        interrupted = false
        autoResume = false
    }
}
