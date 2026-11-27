package com.gamelingo.mobile.capture

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OcrExperimentTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun bmp(): Bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).also {
        for (x in 0 until 8) for (y in 0 until 4) it.setPixel(x, y, if (x % 2 == 0) Color.rgb(100, 100, 100) else Color.rgb(140, 140, 140))
    }

    @Test fun `default is off and off returns the very same bitmap`() {
        ctx.getSharedPreferences("ocr_experiment", 0).edit().clear().commit()
        assertEquals(OcrExperiment.Mode.OFF, OcrExperiment.get(ctx))
        val b = bmp()
        assertSame(b, OcrExperiment.prepare(b, OcrExperiment.Mode.OFF))
    }

    @Test fun `each mode keeps the size, leaves the source untouched and reset returns to off`() {
        val b = bmp()
        val before = b.getPixel(0, 0)
        OcrExperiment.Mode.entries.filter { it != OcrExperiment.Mode.OFF }.forEach { m ->
            val o = OcrExperiment.prepare(b, m)
            assertNotSame(b, o)
            assertEquals(b.width, o.width)
            assertEquals(b.height, o.height)
        }
        assertEquals(before, b.getPixel(0, 0))
        OcrExperiment.set(ctx, OcrExperiment.Mode.THRESHOLD)
        assertEquals(OcrExperiment.Mode.THRESHOLD, OcrExperiment.get(ctx))
        OcrExperiment.reset(ctx)
        assertEquals(OcrExperiment.Mode.OFF, OcrExperiment.get(ctx))
    }

    @Test fun `no mode needs scaling so box coordinates cannot shift`() {
        assertFalse(OcrExperiment.Mode.entries.any { it.option == com.gamelingo.mobile.core.capture.OcrPreprocessor.Option.SCALE_2X })
    }
}
