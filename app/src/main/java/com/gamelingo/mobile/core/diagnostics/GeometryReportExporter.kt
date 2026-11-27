package com.gamelingo.mobile.core.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes the geometry report to the cache and builds the share intent for it. */
object GeometryReportExporter {
    const val DIR = "diagnostics"
    const val FILE_NAME = "geometry-diagnostics.txt"

    fun writeReport(context: Context): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        val header = "app=${context.packageName} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "provenance=${com.gamelingo.mobile.core.capture.CaptureProvenance.forSdk(android.os.Build.VERSION.SDK_INT, !com.gamelingo.mobile.capture.CaptureMode.appWindowActive(context))} picker=${com.gamelingo.mobile.capture.CaptureMode.appWindowActive(context)}"
        file.writeText(GeometryDiagnostics.export(header))
        return file
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun createShareIntent(context: Context, title: String): Intent {
        val uri = uriFor(context, writeReport(context))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
