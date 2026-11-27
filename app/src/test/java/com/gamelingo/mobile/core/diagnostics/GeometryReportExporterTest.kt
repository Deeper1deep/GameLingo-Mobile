package com.gamelingo.mobile.core.diagnostics

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class GeometryReportExporterTest {

    @Test
    fun `export writes the lines and the share intent carries a readable provider uri`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        GeometryDiagnostics.clear()
        GeometryDiagnostics.record("geometry: rebuilt gen=3 frame=2400x1080")
        val intent = GeometryReportExporter.createShareIntent(context, "t")
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val send = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        assertEquals(context.packageName + ".fileprovider", uri.authority)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("geometry: rebuilt gen=3 frame=2400x1080"))
        assertTrue(text.contains("sdk="))
        GeometryDiagnostics.clear()
    }
}
