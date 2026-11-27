package com.gamelingo.mobile.core.audio

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * A downloadable speech model. [md5] and [sizeBytes] come from the publisher's
 * own list (https://alphacephei.com/vosk/models/model-list.json); [sha256] was
 * computed from the file whose publisher MD5 and size matched. All three must
 * match before anything is unpacked or loaded.
 */
data class AsrModelSpec(
    val id: String,
    val url: String,
    val sizeBytes: Long,
    val md5: String,
    val sha256: String,
    /** Folder inside the zip that holds the model. */
    val rootFolder: String,
    val unpackedBytesApprox: Long,
    val license: String,
    val sourcePage: String,
) {
    companion object {
        val SMALL_EN_US = AsrModelSpec(
            id = "vosk-small-en-us-0.15",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            sizeBytes = 41_205_931L,
            md5 = "09ab50ccd62b674cbaa231b825f9c1cb",
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            rootFolder = "vosk-model-small-en-us-0.15",
            unpackedBytesApprox = 70_898_967L,
            license = "Apache 2.0",
            sourcePage = "https://alphacephei.com/vosk/models",
        )
    }
}

object AsrFileChecks {
    private fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }

    /** Streams the file once; returns (md5, sha256) as lowercase hex. */
    fun digests(file: File): Pair<String, String> {
        val md5 = MessageDigest.getInstance("MD5"); val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md5.update(buf, 0, n); sha.update(buf, 0, n) }
        }
        return hex(md5) to hex(sha)
    }

    fun matches(file: File, spec: AsrModelSpec): Boolean {
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val (md5, sha) = digests(file)
        return md5.equals(spec.md5, true) && sha.equals(spec.sha256, true)
    }

    /**
     * Unpacks [zip] into [targetDir], refusing any entry that would land outside it
     * (zip-slip) and any archive larger than [maxBytes] unpacked.
     */
    fun unzipSafely(zip: InputStream, targetDir: File, maxBytes: Long) {
        val root = targetDir.canonicalFile
        root.mkdirs()
        var total = 0L
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                require(out.path == root.path || out.path.startsWith(root.path + File.separator)) { "unsafe entry" }
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = z.read(buf); if (n < 0) break
                        total += n
                        require(total <= maxBytes) { "archive too large" }
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }
}
