package com.gamelingo.mobile.core.util

import java.io.File
import java.security.MessageDigest

object Sha256 {

    private const val BUFFER_SIZE = 8 * 1024

    fun ofString(text: String): String = ofBytes(text.toByteArray(Charsets.UTF_8))

    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun matches(file: File, expectedHex: String): Boolean =
        ofFile(file).equals(expectedHex.trim(), ignoreCase = true)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
