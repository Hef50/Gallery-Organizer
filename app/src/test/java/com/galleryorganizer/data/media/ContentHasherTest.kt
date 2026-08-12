package com.galleryorganizer.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

class ContentHasherTest {

    private fun bytes(seed: Int, size: Int) = Random(seed).nextBytes(size)

    @Test
    fun `the same file always hashes the same`() {
        val data = bytes(1, 4096)
        val first = ContentHasher.hashOf(4096, ByteArrayInputStream(data), 1_700_000_000_000)
        val second = ContentHasher.hashOf(4096, ByteArrayInputStream(data), 1_700_000_000_000)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `different content hashes differently`() {
        val a = ContentHasher.hashOf(100, ByteArrayInputStream(bytes(1, 100)), 0)
        val b = ContentHasher.hashOf(100, ByteArrayInputStream(bytes(2, 100)), 0)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `size is part of the identity, so files sharing a prefix stay distinct`() {
        // Two videos from the same camera can share their first 64 KiB exactly — same
        // container header, same encoder tables. Size is what separates them.
        val prefix = bytes(7, ContentHasher.PREFIX_BYTES)
        val a = ContentHasher.hashOf(10_000_000, prefix, 0)
        val b = ContentHasher.hashOf(20_000_000, prefix, 0)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `date taken is the final tiebreak`() {
        val prefix = bytes(7, 1024)
        assertThat(ContentHasher.hashOf(1024, prefix, 1))
            .isNotEqualTo(ContentHasher.hashOf(1024, prefix, 2))
    }

    @Test
    fun `two copies of one file hash identically, which is what the duplicate finder needs`() {
        val prefix = bytes(3, 2048)
        assertThat(ContentHasher.hashOf(2048, prefix, 555))
            .isEqualTo(ContentHasher.hashOf(2048, prefix, 555))
    }

    @Test
    fun `only the first 64 KiB is read, however large the file`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                return len
            }
        }
        ContentHasher.hashOf(500_000_000, stream, 0)
        assertThat(consumed).isEqualTo(ContentHasher.PREFIX_BYTES)
    }

    @Test
    fun `a short read does not change the hash`() {
        // InputStream.read may legitimately return fewer bytes than asked for. If the
        // hasher took whatever the first read gave it, the same file would hash
        // differently depending on buffer scheduling.
        val data = bytes(9, 5000)
        val dribbling = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val take = minOf(7, len, data.size - pos) // never more than 7 bytes at a time
                System.arraycopy(data, pos, b, off, take)
                pos += take
                return take
            }
        }

        assertThat(ContentHasher.hashOf(5000, dribbling, 0))
            .isEqualTo(ContentHasher.hashOf(5000, ByteArrayInputStream(data), 0))
    }

    @Test
    fun `a file shorter than the prefix window hashes over exactly its own bytes`() {
        val data = bytes(11, 128)
        assertThat(ContentHasher.hashOf(128, ByteArrayInputStream(data), 0))
            .isEqualTo(ContentHasher.hashOf(128, data, 0))
    }
}
