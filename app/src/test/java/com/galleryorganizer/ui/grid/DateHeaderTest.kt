package com.galleryorganizer.ui.grid

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import com.galleryorganizer.data.db.entity.MediaEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateHeaderTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(id: Long, taken: Long) = MediaEntity(
        id = id,
        mediaStoreId = id,
        uri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        bucketId = 1,
        bucketName = "Camera",
        mime = "image/jpeg",
        isVideo = false,
        size = 1,
        dateTaken = taken,
        dateModified = taken / 1000,
        dateAdded = taken / 1000,
        dateFirstIndexed = taken,
    )

    private suspend fun render(items: List<MediaEntity>): List<String> =
        flowOf(PagingData.from(items))
            .withDateHeaders(zone) { it.toString() }
            .asSnapshot()
            .map { entry ->
                when (entry) {
                    is GridEntry.DateHeader -> "== ${entry.label}"
                    is GridEntry.Item -> entry.media.displayName
                }
            }

    @Test
    fun `a header opens each new day and never repeats within one`() = runTest {
        val rendered = render(
            listOf(
                item(1, at(2024, 6, 12, hour = 18)),
                item(2, at(2024, 6, 12, hour = 9)),
                item(3, at(2024, 6, 11)),
            ),
        )

        assertThat(rendered).containsExactly(
            "== 2024-06-12",
            "IMG_1.jpg",
            "IMG_2.jpg",
            "== 2024-06-11",
            "IMG_3.jpg",
        ).inOrder()
    }

    @Test
    fun `the very first item always gets a header`() = runTest {
        assertThat(render(listOf(item(1, at(2024, 1, 1)))))
            .containsExactly("== 2024-01-01", "IMG_1.jpg").inOrder()
    }

    @Test
    fun `there is no trailing header after the last item`() = runTest {
        val rendered = render(listOf(item(1, at(2024, 1, 1)), item(2, at(2023, 12, 31))))
        assertThat(rendered.last()).isEqualTo("IMG_2.jpg")
        assertThat(rendered.count { it.startsWith("==") }).isEqualTo(2)
    }

    @Test
    fun `an empty library renders nothing at all, not a stray header`() = runTest {
        assertThat(render(emptyList())).isEmpty()
    }

    @Test
    fun `sectioning follows the local day, not UTC`() = runTest {
        // 00:30 on 12 June in London is 23:30 on 11 June UTC. Sectioning on UTC would put
        // this photo under the wrong day for anyone east of Greenwich.
        val justAfterLocalMidnight = ZonedDateTime.of(2024, 6, 12, 0, 30, 0, 0, zone)
            .toInstant().toEpochMilli()

        val rendered = render(listOf(item(1, justAfterLocalMidnight)))

        assertThat(rendered.first()).isEqualTo("== ${LocalDate.of(2024, 6, 12)}")
    }
}
