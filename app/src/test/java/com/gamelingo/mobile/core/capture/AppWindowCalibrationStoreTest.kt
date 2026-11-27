package com.gamelingo.mobile.core.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppWindowCalibrationStoreTest {
    @After fun reset() = AppWindowCalibrationStore.clear()

    private val cal = AppWindowCalibration(100, 50, 800, 600, 1000, 800)

    @Test fun `valid only while the content size still matches`() {
        AppWindowCalibrationStore.contentSize = 800 to 600
        AppWindowCalibrationStore.calibration = cal
        assertNotNull(AppWindowCalibrationStore.validFor(1000, 800))
        AppWindowCalibrationStore.contentSize = 700 to 600 // window resized
        assertNull(AppWindowCalibrationStore.validFor(1000, 800))
    }

    @Test fun `a rotated display invalidates it`() {
        AppWindowCalibrationStore.contentSize = 800 to 600
        AppWindowCalibrationStore.calibration = cal
        assertNull(AppWindowCalibrationStore.validFor(800, 1000))
    }

    @Test fun `without a reported content size nothing is valid`() {
        AppWindowCalibrationStore.calibration = cal
        assertNull(AppWindowCalibrationStore.validFor(1000, 800))
    }

    @Test fun `clear forgets everything for the next capture session`() {
        AppWindowCalibrationStore.contentSize = 800 to 600
        AppWindowCalibrationStore.calibration = cal
        AppWindowCalibrationStore.clear()
        assertNull(AppWindowCalibrationStore.calibration)
        assertEquals(null, AppWindowCalibrationStore.contentSize)
    }
}
