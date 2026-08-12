package com.galleryorganizer.ui.grid

import com.galleryorganizer.data.db.entity.MediaEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Guards the launch crash.
 *
 * The date slider reads the date of the topmost visible photo to place its thumb. The first
 * version scanned forward from the top visible index without bounding the scan, and Paging's
 * `peek` *throws* for an index it does not hold rather than returning null. The paged list is
 * empty on the very first frame, before any page has loaded, so `peek(0)` threw and the app
 * died on every launch before it could draw a single photo.
 *
 * Every case below is one where the old code would have gone out of bounds.
 */
class FirstItemDateNearTest {

    private fun item(dateTaken: Long) = GridEntry.Item(
        MediaEntity(
            id = dateTaken,
            mediaStoreId = dateTaken,
            uri = "content://media/external/images/media/$dateTaken",
            displayName = "IMG_$dateTaken.jpg",
            relativePath = "DCIM/Camera/",
            bucketId = 1,
            bucketName = "Camera",
            mime = "image/jpeg",
            isVideo = false,
            size = 1,
            dateTaken = dateTaken,
            dateModified = 1,
            dateAdded = 1,
            dateFirstIndexed = 1,
        ),
    )

    private fun header() = GridEntry.DateHeader(LocalDate.of(2024, 6, 1), "1 June 2024")

    /** Stands in for Paging: throws out of range, exactly as `ItemSnapshotList` does. */
    private fun peeker(entries: List<GridEntry?>): (Int) -> GridEntry? = { index ->
        if (index < 0 || index >= entries.size) {
            throw IndexOutOfBoundsException("Index: $index, Size: ${entries.size}")
        }
        entries[index]
    }

    @Test
    fun `an empty list is never asked for an item`() {
        // The launch crash, exactly: nothing is loaded on the first frame.
        val date = firstItemDateNear(topIndex = 0, itemCount = 0, lookahead = 8, peek = peeker(emptyList()))

        assertThat(date).isNull()
    }

    @Test
    fun `a scan that would run past the end stops at the end`() {
        val entries = listOf(header(), item(100))

        val date = firstItemDateNear(topIndex = 0, itemCount = 2, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(100)
    }

    @Test
    fun `a stale top index from a list that has since shrunk is clamped`() {
        // The grid's layout info can lag a page drop or a refresh by a frame, so the index
        // it reports may not exist any more.
        val entries = listOf(item(100), item(200))

        val date = firstItemDateNear(topIndex = 900, itemCount = 2, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(200)
    }

    @Test
    fun `it looks past a date heading to the photo underneath`() {
        // The topmost entry on screen is very often a heading, which has no date of its own.
        val entries = listOf(header(), item(500), item(400))

        val date = firstItemDateNear(topIndex = 0, itemCount = 3, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(500)
    }

    @Test
    fun `a run of headings longer than the lookahead gives up rather than scanning on`() {
        val entries = List(20) { header() } + item(700)

        val date = firstItemDateNear(topIndex = 0, itemCount = 21, lookahead = 4, peek = peeker(entries))

        assertThat(date).isNull()
    }

    @Test
    fun `a negative index is clamped rather than passed through`() {
        val entries = listOf(item(100))

        val date = firstItemDateNear(topIndex = -5, itemCount = 1, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(100)
    }

    @Test
    fun `a nonsensical lookahead asks for nothing at all`() {
        val entries = listOf(item(100))

        assertThat(firstItemDateNear(0, itemCount = 1, lookahead = 0, peek = peeker(entries))).isNull()
        assertThat(firstItemDateNear(0, itemCount = 1, lookahead = -3, peek = peeker(entries))).isNull()
    }

    @Test
    fun `placeholders in the window are skipped, not treated as photos`() {
        val entries = listOf(null, null, item(300))

        val date = firstItemDateNear(topIndex = 0, itemCount = 3, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(300)
    }

    @Test
    fun `it starts from the top of the screen, not the top of the library`() {
        val entries = listOf(item(900), item(800), item(700))

        val date = firstItemDateNear(topIndex = 2, itemCount = 3, lookahead = 8, peek = peeker(entries))

        assertThat(date).isEqualTo(700)
    }
}
