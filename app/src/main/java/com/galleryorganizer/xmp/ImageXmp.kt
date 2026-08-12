package com.galleryorganizer.xmp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** What the app can write tags into, and how. */
enum class XmpContainer {
    /** XMP goes in an `APP1` segment. */
    Jpeg,

    /** XMP goes in an `iTXt` chunk. */
    Png,

    /** Anything else — a `.xmp` sidecar next to the file. */
    Sidecar,
    ;

    companion object {
        /**
         * HEIC is deliberately [Sidecar]. Writing XMP into HEIF means inserting a `mime`
         * item into the ISO-BMFF `meta` box and rewriting the item location table — a
         * whole-container rewrite where a mistake produces an unopenable photo. This app
         * promises never to corrupt a file, and a sidecar keeps that promise absolutely.
         * See DECISIONS.md and OPEN_QUESTIONS.md.
         */
        fun forMimeType(mime: String): XmpContainer = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> Jpeg
            "image/png" -> Png
            else -> Sidecar
        }
    }
}

/** Thrown when a file is not the shape its mime type claims. Never partially written. */
class MalformedImageException(message: String) : Exception(message)

/**
 * Rewrites a JPEG with the given XMP packet.
 *
 * JPEG is a sequence of marker segments. XMP lives in an `APP1` segment whose payload
 * starts with the XMP namespace and a NUL. The rewrite copies every other segment through
 * byte for byte, drops any existing XMP `APP1`, and inserts the new one immediately after
 * the `SOI` — leaving EXIF, ICC profiles, thumbnails and the entropy-coded image data
 * exactly as they were.
 */
object JpegXmp {

    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP1 = 0xE1

    /** Max payload of one JPEG segment, minus the two length bytes. */
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2

    fun write(source: InputStream, sink: OutputStream, packet: String) {
        val packetBytes = packet.toByteArray(Charsets.UTF_8)
        val payloadSize = XmpPacket.JPEG_XMP_SIGNATURE.size + 1 + packetBytes.size
        if (payloadSize > MAX_SEGMENT_PAYLOAD) {
            // Extended XMP splits across several APP1 segments; rather than write a
            // half-supported version of that, the caller falls back to a sidecar.
            throw MalformedImageException("XMP packet too large for a single APP1 segment")
        }

        val input = source.buffered()
        if (input.read() != MARKER || input.read() != SOI) {
            throw MalformedImageException("Not a JPEG: missing SOI")
        }
        sink.write(MARKER)
        sink.write(SOI)
        writeXmpSegment(sink, packetBytes)

        while (true) {
            val marker = input.readMarker() ?: break
            when (marker) {
                EOI -> {
                    sink.write(MARKER)
                    sink.write(EOI)
                    input.copyTo(sink) // anything trailing, byte for byte
                    return
                }

                SOS -> {
                    // Everything from here on is entropy-coded data with no length field.
                    sink.write(MARKER)
                    sink.write(SOS)
                    input.copyTo(sink)
                    return
                }

                else -> {
                    val length = input.readUInt16()
                    val payload = input.readExactly(length - 2)
                    // Drop any XMP already in the file; the new one is already written.
                    if (marker == APP1 && payload.startsWithXmpSignature()) continue
                    sink.write(MARKER)
                    sink.write(marker)
                    sink.writeUInt16(length)
                    sink.write(payload)
                }
            }
        }
    }

    /** The XMP packet in a JPEG, or null if there is none. */
    fun read(source: InputStream): String? {
        val input = source.buffered()
        if (input.read() != MARKER || input.read() != SOI) return null
        while (true) {
            val marker = input.readMarker() ?: return null
            if (marker == SOS || marker == EOI) return null
            val length = input.readUInt16()
            val payload = runCatching { input.readExactly(length - 2) }.getOrNull() ?: return null
            if (marker == APP1 && payload.startsWithXmpSignature()) {
                val start = XmpPacket.JPEG_XMP_SIGNATURE.size + 1
                return String(payload, start, payload.size - start, Charsets.UTF_8)
            }
        }
    }

    private fun writeXmpSegment(sink: OutputStream, packet: ByteArray) {
        val payloadSize = XmpPacket.JPEG_XMP_SIGNATURE.size + 1 + packet.size
        sink.write(MARKER)
        sink.write(APP1)
        sink.writeUInt16(payloadSize + 2)
        sink.write(XmpPacket.JPEG_XMP_SIGNATURE)
        sink.write(0)
        sink.write(packet)
    }

    private fun ByteArray.startsWithXmpSignature(): Boolean {
        val signature = XmpPacket.JPEG_XMP_SIGNATURE
        if (size < signature.size + 1) return false
        for (i in signature.indices) if (this[i] != signature[i]) return false
        return this[signature.size] == 0.toByte()
    }

    /** Skips fill bytes, which are legal between segments. Returns null at end of stream. */
    private fun InputStream.readMarker(): Int? {
        var byte = read()
        while (byte == MARKER) {
            val next = read()
            if (next == -1) return null
            if (next != MARKER) return next
        }
        if (byte == -1) return null
        // Not sitting on a marker: the file is not a well-formed JPEG.
        while (byte != MARKER) {
            byte = read()
            if (byte == -1) return null
        }
        val next = read()
        return if (next == -1) null else next
    }
}

/**
 * Rewrites a PNG with the given XMP packet in an `iTXt` chunk.
 *
 * PNG is a signature followed by length-prefixed, CRC-checked chunks. The rewrite copies
 * every chunk through, drops any existing XMP `iTXt`, and inserts the new one before
 * `IDAT` — where the spec says textual metadata belongs if it should be available before
 * the image data.
 */
object PngXmp {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun write(source: InputStream, sink: OutputStream, packet: String) {
        val input = source.buffered()
        // A file shorter than the signature is not a PNG either; it must surface as the
        // same "leave this file alone" failure rather than an EOF nobody expects.
        val signature = runCatching { input.readExactly(SIGNATURE.size) }.getOrNull()
        if (signature == null || !signature.contentEquals(SIGNATURE)) {
            throw MalformedImageException("Not a PNG: bad signature")
        }
        sink.write(SIGNATURE)

        var inserted = false
        while (true) {
            val length = runCatching { input.readUInt32() }.getOrNull() ?: break
            val type = String(input.readExactly(4), Charsets.US_ASCII)
            val data = input.readExactly(length)
            input.readExactly(4) // the original CRC; recomputed for anything kept

            if (type == "iTXt" && data.isXmpKeyword()) continue

            if (!inserted && (type == "IDAT" || type == "IEND")) {
                writeChunk(sink, "iTXt", xmpChunkData(packet))
                inserted = true
            }
            writeChunk(sink, type, data)
            if (type == "IEND") break
        }
        // A PNG with no IDAT and no IEND is malformed, but writing the packet anyway is
        // better than silently producing a file with no tags in it.
        if (!inserted) writeChunk(sink, "iTXt", xmpChunkData(packet))
    }

    fun read(source: InputStream): String? {
        val input = source.buffered()
        if (!runCatching { input.readExactly(SIGNATURE.size) }.getOrNull()
                .contentEquals(SIGNATURE)
        ) {
            return null
        }
        while (true) {
            val length = runCatching { input.readUInt32() }.getOrNull() ?: return null
            val type = String(input.readExactly(4), Charsets.US_ASCII)
            val data = input.readExactly(length)
            input.readExactly(4)
            if (type == "iTXt" && data.isXmpKeyword()) {
                // keyword \0 compressionFlag compressionMethod languageTag \0 translated \0
                var offset = XmpPacket.PNG_KEYWORD.length + 1 + 2
                offset = data.skipToAfterNul(offset) // language tag
                offset = data.skipToAfterNul(offset) // translated keyword
                return String(data, offset, data.size - offset, Charsets.UTF_8)
            }
            if (type == "IEND") return null
        }
    }

    private fun xmpChunkData(packet: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(XmpPacket.PNG_KEYWORD.toByteArray(Charsets.US_ASCII))
        out.write(0) // null separator
        out.write(0) // compression flag: uncompressed, so any reader can get at it
        out.write(0) // compression method
        out.write(0) // empty language tag
        out.write(0) // empty translated keyword
        out.write(packet.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun writeChunk(sink: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        sink.writeUInt32(data.size)
        sink.write(typeBytes)
        sink.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        sink.writeUInt32(crc.value.toInt())
    }

    private fun ByteArray.isXmpKeyword(): Boolean {
        val keyword = XmpPacket.PNG_KEYWORD.toByteArray(Charsets.US_ASCII)
        if (size < keyword.size + 1) return false
        for (i in keyword.indices) if (this[i] != keyword[i]) return false
        return this[keyword.size] == 0.toByte()
    }

    private fun ByteArray.skipToAfterNul(from: Int): Int {
        var i = from
        while (i < size && this[i] != 0.toByte()) i++
        return (i + 1).coerceAtMost(size)
    }
}

// --- shared stream helpers -------------------------------------------------------------

internal fun InputStream.readExactly(count: Int): ByteArray {
    require(count >= 0) { "Negative segment length — the file is malformed" }
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(buffer, offset, count - offset)
        if (read <= 0) throw EOFException("Wanted $count bytes, got $offset")
        offset += read
    }
    return buffer
}

internal fun InputStream.readUInt16(): Int {
    val high = read()
    val low = read()
    if (high == -1 || low == -1) throw EOFException("Truncated length field")
    return (high shl 8) or low
}

internal fun InputStream.readUInt32(): Int {
    val bytes = readExactly(4)
    return ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
}

internal fun OutputStream.writeUInt16(value: Int) {
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun OutputStream.writeUInt32(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}
