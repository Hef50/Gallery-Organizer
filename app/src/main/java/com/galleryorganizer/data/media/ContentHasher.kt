package com.galleryorganizer.data.media

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

/**
 * Computes the stable identity of an item.
 *
 * `size:sha256(first 64 KiB):dateTaken`. Hashing whole files across a 150k-item library
 * is minutes of I/O and battery for no benefit — the first 64 KiB of a photo or video
 * already covers the container header and EXIF plus the start of the entropy-coded data,
 * and pairing it with the exact byte size makes an accidental collision vanishingly
 * unlikely for real camera output. `dateTaken` is a final tiebreak.
 *
 * Two byte-identical copies of the same photo *do* hash the same, on purpose: that is
 * what lets the duplicate finder work and what makes a restore tag both copies.
 */
object ContentHasher {

    const val PREFIX_BYTES = 64 * 1024

    /** Pure, and therefore testable without a device. */
    fun hashOf(size: Long, prefix: ByteArray, dateTaken: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(prefix)
        return buildString(digest.size * 2 + 32) {
            append(size)
            append(':')
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
            append(':')
            append(dateTaken)
        }
    }

    fun hashOf(size: Long, stream: InputStream, dateTaken: Long): String =
        hashOf(size, stream.readPrefix(PREFIX_BYTES), dateTaken)

    /**
     * Returns null when the file cannot be opened — deleted mid-scan, on an unmounted
     * card, or blocked by a partial grant. A failure to hash is never fatal: the item
     * simply stays unhashed and the backfill worker retries later.
     */
    fun hashOf(resolver: ContentResolver, uri: Uri, size: Long, dateTaken: Long): String? = try {
        resolver.openInputStream(uri)?.use { hashOf(size, it, dateTaken) }
    } catch (e: SecurityException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Reads up to [limit] bytes. `InputStream.read` is free to return short reads, and a
 * naive single `read()` would hash a different prefix depending on buffer scheduling —
 * which would make the hash unstable across runs for the same file.
 */
internal fun InputStream.readPrefix(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val read = read(buffer, offset, limit - offset)
        if (read <= 0) break
        offset += read
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
}
