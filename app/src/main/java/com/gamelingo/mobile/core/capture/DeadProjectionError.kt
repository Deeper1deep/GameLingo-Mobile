package com.gamelingo.mobile.core.capture

/**
 * Android 14+ kills a MediaProjection it considers ended or superseded, and
 * every later createVirtualDisplay on it dies with
 * "SecurityException: Cannot create VirtualDisplay with non-current
 * MediaProjection". Retrying that is pointless: the projection is gone and
 * only fresh user consent can replace it. This predicate lets the rebuild
 * loop recognize the corpse and stop burning its retry budget.
 */
fun isDeadProjectionError(error: Throwable): Boolean =
    error is SecurityException &&
        (error.message?.contains("non-current MediaProjection") == true)
