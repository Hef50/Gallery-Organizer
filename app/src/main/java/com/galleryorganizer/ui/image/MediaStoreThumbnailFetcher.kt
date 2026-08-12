package com.galleryorganizer.ui.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import okio.Buffer
import okio.buffer
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * The sizes a thumbnail is ever generated at.
 *
 * Snapping requests to a small set of buckets does two jobs. It keeps cache keys stable
 * when a cell measures a pixel differently between one layout and the next — otherwise a
 * 350 px and a 352 px request are two different cached thumbnails of the same photo. And
 * the cap matters more: above roughly a thousand pixels MediaProvider has nothing cached
 * and has to decode the original, so an uncapped request from the one-column grid means
 * decoding a 200 MP JPEG for every visible tile.
 */
internal val ThumbnailBuckets = intArrayOf(384, 768, 1024)

internal const val DEFAULT_THUMBNAIL_PX = 384

/**
 * The size a request of [requestedPx] is actually served at. Pure, so it is testable.
 *
 * There is deliberately no bucket below [DEFAULT_THUMBNAIL_PX], and that is a scrolling
 * decision rather than a quality one. Every dense zoom level — four, six and ten columns —
 * lands on the same bucket, so pinching between them reuses one cached thumbnail per photo
 * instead of re-fetching the entire visible grid at a new size. Coil still downsamples to
 * the cell when it decodes, so the ten-column grid holds ten-column-sized bitmaps in memory;
 * only the file on disk is shared.
 */
internal fun thumbnailBucketFor(requestedPx: Int): Int = when {
    requestedPx <= 0 -> DEFAULT_THUMBNAIL_PX
    else -> ThumbnailBuckets.firstOrNull { it >= requestedPx } ?: ThumbnailBuckets.last()
}

/**
 * The largest dimension a request is bounded by, or null when it is not bounded at all.
 *
 * Null means `Size.ORIGINAL`, which is the full-screen viewer asking for the real photograph,
 * and it has to be distinguishable from a request for zero pixels. Reading an undefined
 * dimension as `0` — which is what `pxOrElse { 0 }` does — silently turned the viewer into a
 * request for the smallest bucket, so opening a photo full screen showed a 384-pixel
 * thumbnail blown up to fill the screen and pinching into it revealed nothing. That is the
 * whole reason this returns `Int?` rather than `Int`.
 */
internal fun boundedSizeOf(widthPx: Int?, heightPx: Int?): Int? {
    if (widthPx == null && heightPx == null) return null
    return max(widthPx ?: 0, heightPx ?: 0).takeIf { it > 0 }
}

/** The disk-cache key for a photo at a bucket. Pure, so it is testable. */
internal fun thumbnailCacheKey(uri: Uri, bucketPx: Int): String = "$uri|$bucketPx"

/**
 * Loads grid thumbnails from MediaStore's pre-generated thumbnails, and keeps a persistent
 * copy of every one it has to generate.
 *
 * Two things made scrolling back through old photos stutter, and this class exists to fix
 * both.
 *
 * **MediaStore only has a thumbnail if something asked for one recently.** For photos from
 * last week the system cache is warm and `loadThumbnail` is nearly free. For photos from
 * four years ago it usually is not, and MediaProvider quietly falls back to decoding the
 * original — which on this phone is a 200 MP, ~50 MB JPEG. Hundreds of milliseconds of CPU
 * per tile, in another process, which is why the grid felt fine at the top of the library
 * and fell apart further down it.
 *
 * **Coil's disk cache does not help on its own.** In Coil 2, `HttpUriFetcher` is the only
 * thing that ever writes to it, so a `content://` thumbnail was regenerated from scratch
 * every time it fell out of the in-memory cache — scroll down through a few thousand old
 * photos and back up, and every one was decoded from the original again. So this fetcher
 * writes through to the disk cache itself. MediaProvider is asked at most once per photo
 * per bucket, ever; every later visit reads a file of a few tens of kilobytes.
 */
class MediaStoreThumbnailFetcher(
    private val data: Uri,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val requested = boundedSizeOf(
            widthPx = (options.size.width as? Dimension.Pixels)?.px,
            heightPx = (options.size.height as? Dimension.Pixels)?.px,
        )

        // Unbounded — the full-screen viewer wants the photograph itself. Hand over the
        // original and let Coil subsample it to the screen; a thumbnail here is something
        // the user can see through, and can pinch into and find nothing behind.
        if (requested == null) return streamOriginal()

        val bucket = thumbnailBucketFor(requested)
        val key = thumbnailCacheKey(data, bucket)

        // Fast path: this exact thumbnail has been generated before, by any earlier run.
        readFromDiskCache(key)?.let { return it }

        val thumbnail = runCatching {
            options.context.contentResolver
                .loadThumbnail(data, android.util.Size(bucket, bucket), null)
        }.getOrNull()

        if (thumbnail != null) {
            val bytes = thumbnail.encode()
            // Hand Coil the cached *file* rather than the bitmap. It costs one small decode
            // now, saves a MediaProvider round trip for the rest of the app's life, and
            // lets Coil produce a hardware bitmap — so a fling is not re-uploading software
            // bitmaps to the GPU.
            writeToDiskCache(key, bytes)?.let { return it }

            return SourceResult(
                source = ImageSource(Buffer().apply { write(bytes) }, options.context),
                mimeType = thumbnail.mimeType(),
                dataSource = DataSource.DISK,
            )
        }

        // No thumbnail at all — a file the media scanner has not reached yet.
        return streamOriginal()
    }

    /**
     * The original bytes, for Coil to decode and subsample itself.
     *
     * Not written to the disk cache: these are whole photographs, and a handful of 50 MB
     * originals would evict every thumbnail in the cache to hold images the viewer will ask
     * for again at a different zoom anyway.
     */
    private fun streamOriginal(): SourceResult {
        val stream = options.context.contentResolver.openInputStream(data)
            ?: throw IOException("Unable to open $data")
        return SourceResult(
            source = ImageSource(stream.source().buffer(), options.context),
            mimeType = options.context.contentResolver.getType(data),
            dataSource = DataSource.DISK,
        )
    }

    private fun readFromDiskCache(key: String): SourceResult? {
        val cache = diskCache ?: return null
        val snapshot = runCatching { cache.openSnapshot(key) }.getOrNull() ?: return null
        return SourceResult(
            source = ImageSource(
                file = snapshot.data,
                fileSystem = cache.fileSystem,
                diskCacheKey = key,
                closeable = snapshot,
            ),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun writeToDiskCache(key: String, bytes: ByteArray): SourceResult? {
        val cache = diskCache ?: return null
        val editor = runCatching { cache.openEditor(key) }.getOrNull() ?: return null
        val written = runCatching {
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        }.isSuccess
        if (!written) {
            runCatching { editor.abort() }
            return null
        }
        return readFromDiskCache(key)
    }

    /**
     * The disk cache is handed in lazily rather than captured: the singleton `ImageLoader`
     * builds its own disk cache while it is being constructed, so reading it eagerly here
     * would be a cycle.
     */
    class Factory(private val diskCache: () -> DiskCache?) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == ContentResolver.SCHEME_CONTENT && data.authority == MEDIA_AUTHORITY) {
                MediaStoreThumbnailFetcher(data, options, diskCache())
            } else {
                // Anything else falls through to Coil's own fetchers.
                null
            }

        private companion object {
            const val MEDIA_AUTHORITY = "media"
        }
    }

    private companion object {
        const val THUMBNAIL_QUALITY = 88

        fun Bitmap.encode(): ByteArray {
            val out = ByteArrayOutputStream(32 * 1024)
            // Screenshots and PNGs can carry transparency, and JPEG would turn it black.
            compress(compressFormat(), THUMBNAIL_QUALITY, out)
            return out.toByteArray()
        }

        fun Bitmap.compressFormat(): Bitmap.CompressFormat =
            if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        fun Bitmap.mimeType(): String = if (hasAlpha()) "image/png" else "image/jpeg"
    }
}
