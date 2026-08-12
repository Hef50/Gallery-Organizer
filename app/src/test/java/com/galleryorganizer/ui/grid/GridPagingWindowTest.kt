package com.galleryorganizer.ui.grid

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.testing.asSnapshot
import com.galleryorganizer.data.db.DbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What the grid's paging window actually contains as you scroll deep into the library.
 *
 * This exists because the viewer is opened with an *index*, and an index is only meaningful
 * if it means the same thing to the grid and to the query behind it.
 */
class GridPagingWindowTest : DbTest() {

    private fun pagerOver(config: PagingConfig) = Pager(config) {
        db.mediaDao().pagingSourceAll(includeMissing = false)
    }

    @Test
    fun `under a maxSize cap a deep position is not addressable at all`() = runTest {
        // Newest first, so position 0 is id 1000 and the oldest is id 1.
        media.insertAll((1L..1_000L).map { sampleMedia(it) })

        val config = PagingConfig(
            pageSize = 20,
            prefetchDistance = 20,
            initialLoadSize = 40,
            enablePlaceholders = false,
            maxSize = 100,
        )

        // With placeholders off, a capped window does not grow: pages dropped from the
        // front cancel out pages appended at the back, so the list the UI sees stays short
        // and its indices slide as you scroll. Position 400 in the *query* is therefore not
        // position 400 in the list, and asking for it fails outright.
        val failure = runCatching { pagerOver(config).flow.asSnapshot { scrollTo(400) } }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThat(failure).hasMessageThat().contains("Size:")
    }

    @Test
    fun `without a maxSize cap the window still starts at the newest photo`() = runTest {
        media.insertAll((1L..1_000L).map { sampleMedia(it) })

        val config = PagingConfig(
            pageSize = 20,
            prefetchDistance = 20,
            initialLoadSize = 40,
            enablePlaceholders = false,
        )

        val loaded = pagerOver(config).flow.asSnapshot { scrollTo(400) }

        // Nothing is dropped, so a grid index is exactly an offset into the query — which
        // is what the viewer relies on to open the photo that was actually tapped.
        assertThat(loaded.first().mediaStoreId).isEqualTo(1_000L)
        assertThat(loaded[10].mediaStoreId).isEqualTo(990L)
    }

    /**
     * The one that actually guards the app: the grid's own configuration, not a synthetic
     * one. If a `maxSize` is ever put back, deep positions stop matching the query and this
     * fails.
     */
    @Test
    fun `the grid's real config keeps a deep position matching the query`() = runTest {
        media.insertAll((1L..3_000L).map { sampleMedia(it) })

        val config = PagingConfig(
            pageSize = GalleryViewModel.PAGE_SIZE,
            prefetchDistance = GalleryViewModel.PAGE_SIZE,
            initialLoadSize = GalleryViewModel.PAGE_SIZE * 2,
            enablePlaceholders = false,
        )

        val loaded = pagerOver(config).flow.asSnapshot { scrollTo(2_000) }

        // Position n in the list is position n in the query, however far down it is.
        assertThat(loaded[0].mediaStoreId).isEqualTo(3_000L)
        assertThat(loaded[1_500].mediaStoreId).isEqualTo(3_000L - 1_500L)
        assertThat(loaded[2_000].mediaStoreId).isEqualTo(3_000L - 2_000L)
    }
}
