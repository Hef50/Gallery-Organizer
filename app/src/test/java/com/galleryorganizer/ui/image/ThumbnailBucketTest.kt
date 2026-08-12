package com.galleryorganizer.ui.image

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pure decisions in the thumbnail path. Both are performance decisions, and both are the
 * kind that regress silently: nothing *fails* if a thumbnail is fetched at the wrong size, it
 * is just fifty times more expensive, or fifty times blurrier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThumbnailBucketTest {

    // --- What "unbounded" means -----------------------------------------------------------

    @Test
    fun `an unbounded request is not a request for zero pixels`() {
        // The viewer asks for Size.ORIGINAL, which has no pixel dimensions at all. Reading
        // those as 0 quietly turned "give me the photograph" into "give me the smallest
        // thumbnail you have", so opening a photo full screen showed a 384 px image blown up
        // across the screen and pinching into it revealed nothing. Null means "the original".
        assertThat(boundedSizeOf(null, null)).isNull()
    }

    @Test
    fun `a request bounded in either direction is a thumbnail request`() {
        assertThat(boundedSizeOf(360, 360)).isEqualTo(360)
        assertThat(boundedSizeOf(360, null)).isEqualTo(360)
        assertThat(boundedSizeOf(null, 720)).isEqualTo(720)
        // The larger side wins: a wide cell still needs enough detail across its width.
        assertThat(boundedSizeOf(1000, 200)).isEqualTo(1000)
    }

    @Test
    fun `a bounded request of zero pixels is treated as unmeasured, not as original`() {
        // A cell that has not been measured yet should fall back to the grid default rather
        // than pulling a 50 MB original for a tile.
        assertThat(boundedSizeOf(0, 0)).isNull()
        assertThat(thumbnailBucketFor(0)).isEqualTo(DEFAULT_THUMBNAIL_PX)
    }

    // --- Buckets --------------------------------------------------------------------------

    @Test
    fun `every dense zoom level shares one cached thumbnail`() {
        // The whole point of dropping the smallest bucket. On this phone four columns is
        // ~360 px, six is ~240 and ten is ~144; if those resolved to different buckets, then
        // pinching out re-fetched every visible tile at a new size, which is exactly when the
        // grid felt worst.
        val fourColumns = thumbnailBucketFor(360)
        val sixColumns = thumbnailBucketFor(240)
        val tenColumns = thumbnailBucketFor(144)

        assertThat(fourColumns).isEqualTo(sixColumns)
        assertThat(sixColumns).isEqualTo(tenColumns)
        assertThat(tenColumns).isEqualTo(DEFAULT_THUMBNAIL_PX)
    }

    @Test
    fun `a request is served at the next bucket up, never below what was asked for`() {
        // Serving below the request would be a visible quality regression.
        assertThat(thumbnailBucketFor(384)).isEqualTo(384)
        assertThat(thumbnailBucketFor(385)).isEqualTo(768)
        assertThat(thumbnailBucketFor(768)).isEqualTo(768)
        assertThat(thumbnailBucketFor(900)).isEqualTo(1024)
    }

    @Test
    fun `an enormous bounded request is capped rather than passed through`() {
        // The one-column grid asks for ~1440 px. MediaProvider has nothing cached that large
        // and would decode the 200 MP original for every visible tile.
        assertThat(thumbnailBucketFor(1440)).isEqualTo(1024)
        assertThat(thumbnailBucketFor(4032)).isEqualTo(1024)
    }

    // --- Cache keys -----------------------------------------------------------------------

    @Test
    fun `neighbouring cell measurements share one cached thumbnail`() {
        // A cell that measures 350 px in one layout pass and 352 in the next must not produce
        // two cache entries for the same photo.
        val uri = Uri.parse("content://media/external/images/media/42")

        assertThat(thumbnailCacheKey(uri, thumbnailBucketFor(350)))
            .isEqualTo(thumbnailCacheKey(uri, thumbnailBucketFor(352)))
    }

    @Test
    fun `the one-column grid does not evict the dense grid`() {
        // These genuinely want different thumbnails, so they get separate entries rather than
        // thrashing a single one.
        val uri = Uri.parse("content://media/external/images/media/42")

        assertThat(thumbnailCacheKey(uri, thumbnailBucketFor(150)))
            .isNotEqualTo(thumbnailCacheKey(uri, thumbnailBucketFor(1440)))
    }

    @Test
    fun `two photos never share a cache key`() {
        val one = Uri.parse("content://media/external/images/media/1")
        val two = Uri.parse("content://media/external/images/media/2")

        assertThat(thumbnailCacheKey(one, 384)).isNotEqualTo(thumbnailCacheKey(two, 384))
    }
}
