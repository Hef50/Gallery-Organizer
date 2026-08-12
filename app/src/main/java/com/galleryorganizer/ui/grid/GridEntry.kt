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

/** For sorts where a date heading would be meaningless, such as "largest first". */
fun Flow<PagingData<MediaEntity>>.withoutHeaders(): Flow<PagingData<GridEntry>> =
    map { data -> data.map<MediaEntity, GridEntry> { GridEntry.Item(it) } }
