package com.gamelingo.mobile.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.gamelingo.mobile.core.util.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guarantees that a downloaded APK is a genuine next build of THIS app:
 * same package name and signed with the exact same certificate as the
 * installed app. A mismatch means the file never reaches the installer.
 */
@Singleton
class SignatureVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** SHA-256 of the certificate that signed the currently installed app. */
    fun installedCertSha256(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        extractCertSha256(info)
    }.getOrNull()

    fun apkPackageName(apk: File): String? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    }.getOrNull()

    /** The versionCode declared inside a downloaded APK file. */
    @Suppress("DEPRECATION")
    fun apkVersionCode(apk: File): Long? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }
    }.getOrNull()

    /** SHA-256 of the certificate embedded in a downloaded APK file. */
    fun apkCertSha256(apk: File): String? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
        info?.let(::extractCertSha256)
    }.getOrNull()

    /** Both checks together; the only gate the installer path consults. */
    fun isGenuineUpdate(apk: File): Boolean {
        val apkPackage = apkPackageName(apk) ?: return false
        if (apkPackage != context.packageName) return false
        val installed = installedCertSha256() ?: return false
        val downloaded = apkCertSha256(apk) ?: return false
        return installed.equals(downloaded, ignoreCase = true)
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun extractCertSha256(info: PackageInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            val signers =
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            signers?.firstOrNull()?.let { Sha256.ofBytes(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.let { Sha256.ofBytes(it.toByteArray()) }
        }
}
