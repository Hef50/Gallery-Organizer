package com.galleryorganizer.ui.grid

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.galleryorganizer.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the grid renders. Date headers are inserted into the paged stream with
 * `PagingData.insertSeparators` rather than computed from a materialised list — that is
 * the only way to have real date sections without ever holding 150k rows in memory.
 */
sealed interface GridEntry {

    data class Item(val media: MediaEntity) : GridEntry {
        val key: String get() = "m${media.id}"
    }

    data class DateHeader(val day: LocalDate, val label: String) : GridEntry {
        val key: String get() = "h$day"
    }
}

/** The local day an item belongs to. Sections are per-day in the device's zone. */
fun MediaEntity.localDay(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(dateTaken).atZone(zone).toLocalDate()

/**
 * Splices date headers into a paged stream of media.
 *
 * Extracted from the view model so it can be tested directly: getting this wrong shows up
 * as a duplicated or missing header at a page boundary, which is exactly the case a
 * hand-rolled "compare with the previous item" loop gets wrong.
 */
fun Flow<PagingData<MediaEntity>>.withDateHeaders(
    zone: ZoneId,
    label: (LocalDate) -> String,
): Flow<PagingData<GridEntry>> = map { data ->
    data.map<MediaEntity, GridEntry> { GridEntry.Item(it) }
        .insertSeparators { before, after ->
            when {
                // Paging calls this with after == null at the end of the list; there is
                // no trailing header.
                after !is GridEntry.Item -> null
                // ...and with before == null at the very start, which always needs one.
                before !is GridEntry.Item -> GridEntry.DateHeader(after.day(zone), label(after.day(zone)))
                before.day(zone) != after.day(zone) ->
                    GridEntry.DateHeader(after.day(zone), label(after.day(zone)))

                else -> null
            }
        }
}

private fun GridEntry.Item.day(zone: ZoneId): LocalDate = media.localDay(zone)

/**
 * The `date_taken` of the first real photo at or just after [topIndex].
 *
 * Used to place the date slider's thumb, which is why it looks past [topIndex]: the topmost
 * entry on screen is very often a date heading, and a heading carries no date of its own.
 *
 * **Every index it touches is bounded by [itemCount], and that is the whole point of this
 * function existing separately.** Paging's `peek` throws rather than returning null when
 * asked for an index it does not have, and the list is empty on the first frame — before any
 * page has loaded — so an unguarded scan crashes the app on launch. It did exactly that.
 *
 * @param peek reads a loaded entry; only ever called with an index known to be in range.
 * @return null when there is nothing loaded to take a date from, which the caller should
 *   treat as "leave the thumb where it is" rather than as position zero.
 */
internal fun firstItemDateNear(
    topIndex: Int,
    itemCount: Int,
    lookahead: Int,
    peek: (Int) -> GridEntry?,
): Long? {
    if (itemCount <= 0 || lookahead <= 0) return null
    val start = topIndex.coerceIn(0, itemCount - 1)
    val end = minOf(start + lookahead, itemCount)
    for (index in start until end) {
        val entry = peek(index)
        if (entry is GridEntry.Item) return entry.media.dateTaken
    }
    return null
}

/** For sorts where a date heading would be meaningless, such as "largest first". */
fun Flow<PagingData<MediaEntity>>.withoutHeaders(): Flow<PagingData<GridEntry>> =
    map { data -> data.map<MediaEntity, GridEntry> { GridEntry.Item(it) } }
