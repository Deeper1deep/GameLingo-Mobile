package com.gamelingo.mobile.core.update

import java.io.File
import java.io.InputStream

/**
 * Streams an APK download to disk with progress callbacks. Pure I/O so it is
 * unit-testable without a network; the caller verifies SHA-256 afterwards and
 * deletes the file on any failure.
 */
object StreamCopier {

    private const val BUFFER_SIZE = 16 * 1024

    /**
     * Copies [input] to [dest], reporting total bytes written after each buffer.
     * Returns the final byte count. Throws IOException on read/write failure;
     * [dest] is left deleted in that case so a partial file never survives.
     */
    fun copy(input: InputStream, dest: File, onProgress: (Long) -> Unit = {}): Long {
        var written = 0L
        try {
            input.use { source ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        return written
    }
}
