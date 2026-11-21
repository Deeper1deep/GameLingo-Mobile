package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android 14+ "non-current MediaProjection" SecurityException means the
 * projection is gone for good; other failures stay retryable.
 */
class DeadProjectionErrorTest {

    @Test
    fun `the non-current security exception is a dead projection`() {
        val error = SecurityException("Cannot create VirtualDisplay with non-current MediaProjection")
        assertTrue(isDeadProjectionError(error))
    }

    @Test
    fun `other security exceptions are not`() {
        assertFalse(isDeadProjectionError(SecurityException("Media projections require a foreground service")))
    }

    @Test
    fun `other exception types are not`() {
        assertFalse(isDeadProjectionError(IllegalStateException("non-current MediaProjection")))
        assertFalse(isDeadProjectionError(RuntimeException("boom")))
    }
}
