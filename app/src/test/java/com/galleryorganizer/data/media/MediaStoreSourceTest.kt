package com.galleryorganizer.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real ContentResolver-backed scanner against a fake media provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaStoreSourceTest {

    private lateinit var source: ContentResolverMediaStoreSource

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(FakeMediaProvider::class.java).create("media")
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = ContentResolverMediaStoreSource(context.contentResolver)
    }

    private suspend fun scanAll(
        collection: MediaCollection = MediaCollection.Images,
        after: ScanCursor = ScanCursor.Start,
        chunkSize: Int = 500,
    ): Pair<List<MediaStoreItem>, List<Int>> {
        val items = mutableListOf<MediaStoreItem>()
        val chunkSizes = mutableListOf<Int>()
        source.scan(collection, after, chunkSize) { chunk ->
            chunkSizes += chunk.size
            items += chunk
        }
        return items to chunkSizes
    }

    @Test
    fun `every projected column is mapped onto the right field`() = runTest {
        FakeMediaProvider.items = listOf(
            FakeMediaProvider.Row(
                mediaStoreId = 42,
                displayName = "IMG_0042.jpg",
                relativePath = "DCIM/Camera/",
                bucketId = 7,
                bucketName = "Camera",
                mime = "image/jpeg",
                size = 123_456,
                dateTaken = 1_700_000_111_000,
                dateModified = 1_700_000_111,
                dateAdded = 1_700_000_112,
                width = 4032,
                height = 3024,
                orientation = 90,
            ),
        )

        val item = scanAll().first.single()

        assertThat(item.mediaStoreId).isEqualTo(42)
        assertThat(item.uri).isEqualTo("content://media/external/images/media/42")
        assertThat(item.displayName).isEqualTo("IMG_0042.jpg")
        assertThat(item.relativePath).isEqualTo("DCIM/Camera/")
        assertThat(item.bucketId).isEqualTo(7)
        assertThat(item.bucketName).isEqualTo("Camera")
        assertThat(item.mime).isEqualTo("image/jpeg")
        assertThat(item.isVideo).isFalse()
        assertThat(item.size).isEqualTo(123_456)
        assertThat(item.dateTaken).isEqualTo(1_700_000_111_000)
        assertThat(item.dateModified).isEqualTo(1_700_000_111)
        assertThat(item.width).isEqualTo(4032)
        assertThat(item.orientation).isEqualTo(90)
    }

    @Test
    fun `a file with no DATE_TAKEN falls back to DATE_MODIFIED rather than the epoch`() = runTest {
        // Downloads, screenshots and anything not from a camera routinely have no
        // DATE_TAKEN. Left at 0 they would all pile up at the bottom of the grid.
        FakeMediaProvider.items = listOf(
            FakeMediaProvider.Row(mediaStoreId = 1, dateTaken = null, dateModified = 1_600_000_000),
        )

        val item = scanAll().first.single()

        assertThat(item.dateTaken).isEqualTo(1_600_000_000_000L)
    }

    @Test
    fun `results come back oldest-modification first so the watermark advances monotonically`() =
        runTest {
            FakeMediaProvider.items = listOf(
                FakeMediaProvider.Row(mediaStoreId = 3, dateModified = 300),
                FakeMediaProvider.Row(mediaStoreId = 1, dateModified = 100),
                FakeMediaProvider.Row(mediaStoreId = 2, dateModified = 200),
            )

            val items = scanAll().first

            assertThat(items.map { it.mediaStoreId }).containsExactly(1L, 2L, 3L).inOrder()
        }

    @Test
    fun `the cursor resumes strictly after the last committed item`() = runTest {
        FakeMediaProvider.items = (1L..5L).map {
            FakeMediaProvider.Row(mediaStoreId = it, dateModified = it * 100)
        }

        val items = scanAll(after = ScanCursor(dateModified = 300, mediaStoreId = 3)).first

        assertThat(items.map { it.mediaStoreId }).containsExactly(4L, 5L).inOrder()
    }

    @Test
    fun `items sharing a timestamp are split by id, so a resume neither repeats nor skips`() =
        runTest {
            // A burst of shots lands in the same second. If the cursor were the timestamp
            // alone, resuming would either re-read the whole second or skip the rest of it.
            FakeMediaProvider.items = (1L..4L).map {
                FakeMediaProvider.Row(mediaStoreId = it, dateModified = 500)
            }

            val items = scanAll(after = ScanCursor(dateModified = 500, mediaStoreId = 2)).first

            assertThat(items.map { it.mediaStoreId }).containsExactly(3L, 4L).inOrder()
        }

    @Test
    fun `the scan is delivered in chunks of the requested size`() = runTest {
        FakeMediaProvider.items = (1L..7L).map {
            FakeMediaProvider.Row(mediaStoreId = it, dateModified = it)
        }

        val (items, chunkSizes) = scanAll(chunkSize = 3)

        assertThat(items).hasSize(7)
        assertThat(chunkSizes).containsExactly(3, 3, 1).inOrder()
    }

    @Test
    fun `images and videos are separate collections`() = runTest {
        FakeMediaProvider.items = listOf(
            FakeMediaProvider.Row(mediaStoreId = 1),
            FakeMediaProvider.Row(
                mediaStoreId = 2,
                isVideo = true,
                mime = "video/mp4",
                duration = 15_000,
            ),
        )

        val images = scanAll(MediaCollection.Images).first
        val videos = scanAll(MediaCollection.Videos).first

        assertThat(images.map { it.mediaStoreId }).containsExactly(1L)
        assertThat(videos.single().isVideo).isTrue()
        assertThat(videos.single().duration).isEqualTo(15_000)
        assertThat(videos.single().uri).isEqualTo("content://media/external/video/media/2")
    }

    @Test
    fun `the id-only sweep query returns every id in the collection`() = runTest {
        FakeMediaProvider.items = (1L..4L).map { FakeMediaProvider.Row(mediaStoreId = it) }

        assertThat(source.allIds(MediaCollection.Images).toList())
            .containsExactly(1L, 2L, 3L, 4L)
    }
}
