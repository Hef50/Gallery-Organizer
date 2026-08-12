package com.galleryorganizer.ui.image

import android.content.ContentResolver
import android.net.Uri
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse
import okio.buffer
import okio.source
import java.io.IOException

/**
 * Loads grid thumbnails from MediaStore's own pre-generated thumbnails instead of
 * decoding the original.
 *
 * This matters a lot on this phone: a 200 MP Galaxy S25 Ultra JPEG is ~50 MB and decoding
 * even a subsampled version of it costs tens of milliseconds and a large transient
 * allocation. `ContentResolver.loadThumbnail` hands back a small bitmap the system has
 * already generated and cached, which is the difference between a grid that flings and
 * one that stutters.
 *
 * If there is no thumbnail — a file the media scanner has not got to yet — this falls
 * back to streaming the original so Coil can subsample it. It never decodes at full size.
 */
class MediaStoreThumbnailFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val resolver = options.context.contentResolver
        val target = options.size.toAndroidSize()

        val thumbnail = runCatching { resolver.loadThumbnail(data, target, null) }.getOrNull()
        if (thumbnail != null) {
            return DrawableResult(
                drawable = thumbnail.toDrawable(options.context.resources),
                // The bitmap is already smaller than the source, which is what isSampled
                // tells Coil so it does not try to treat it as a full-resolution decode.
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        }

        val stream = resolver.openInputStream(data)
            ?: throw IOException("Unable to open $data")
        return SourceResult(
            source = ImageSource(stream.source().buffer(), options.context),
            mimeType = resolver.getType(data),
            dataSource = DataSource.DISK,
        )
    }

    private fun Size.toAndroidSize(): android.util.Size {
        val w = width.pxOrElse { DEFAULT_THUMBNAIL_PX }
        val h = height.pxOrElse { DEFAULT_THUMBNAIL_PX }
        return android.util.Size(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == ContentResolver.SCHEME_CONTENT && data.authority == MEDIA_AUTHORITY) {
                MediaStoreThumbnailFetcher(data, options)
            } else {
                // Anything else falls through to Coil's own fetchers.
                null
            }
    }

    private companion object {
        const val MEDIA_AUTHORITY = "media"
        const val DEFAULT_THUMBNAIL_PX = 512
    }
}
