package com.galleryorganizer.ui.image

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two pure decisions in the thumbnail path. Both of them are performance decisions, and
 * both are the kind that silently regress: nothing fails if a thumbnail is requested at
 * 1440 px, it is just fifty times more expensive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThumbnailBucketTest {

    @Test
    fun `a request is served at the next bucket up, never below what was asked for`() {
        // Serving *below* the request would be a visible quality regression, so the bucket
        // always rounds up.
        assertThat(thumbnailBucketFor(100)).isEqualTo(192)
        assertThat(thumbnailBucketFor(192)).isEqualTo(192)
        assertThat(thumbnailBucketFor(193)).isEqualTo(384)
        assertThat(thumbnailBucketFor(700)).isEqualTo(768)
    }

    @Test
    fun `an enormous request is capped rather than passed through`() {
        // A one-column grid on this phone asks for ~1440 px. MediaProvider has nothing
        // cached at that size and would decode the 200 MP original for every visible tile,
        // which is exactly the cost the whole class exists to avoid.
        assertThat(thumbnailBucketFor(1440)).isEqualTo(1024)
        assertThat(thumbnailBucketFor(4032)).isEqualTo(1024)
    }

    @Test
    fun `an unresolved size falls back to the grid default rather than to zero`() {
        assertThat(thumbnailBucketFor(0)).isEqualTo(DEFAULT_THUMBNAIL_PX)
        assertThat(thumbnailBucketFor(-1)).isEqualTo(DEFAULT_THUMBNAIL_PX)
    }

    @Test
    fun `neighbouring cell measurements share one cached thumbnail`() {
        // The point of bucketing: a cell that measures 350 px in one layout pass and 352 in
        // the next must not produce two cache entries for the same photo.
        val uri = Uri.parse("content://media/external/images/media/42")

        val a = thumbnailCacheKey(uri, thumbnailBucketFor(350))
        val b = thumbnailCacheKey(uri, thumbnailBucketFor(352))

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different densities do not evict each other`() {
        // The four-column and one-column grids want genuinely different thumbnails, so they
        // get separate entries instead of thrashing a single one.
        val uri = Uri.parse("content://media/external/images/media/42")

        val dense = thumbnailCacheKey(uri, thumbnailBucketFor(150))
        val single = thumbnailCacheKey(uri, thumbnailBucketFor(1440))

        assertThat(dense).isNotEqualTo(single)
    }

    @Test
    fun `two photos never share a cache key`() {
        val one = Uri.parse("content://media/external/images/media/1")
        val two = Uri.parse("content://media/external/images/media/2")

        assertThat(thumbnailCacheKey(one, 384)).isNotEqualTo(thumbnailCacheKey(two, 384))
    }
}
