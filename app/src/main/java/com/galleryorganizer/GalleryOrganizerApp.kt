package com.galleryorganizer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.ui.image.MediaStoreThumbnailFetcher

/**
 * Owns the single process-scoped dependency graph. There is no DI framework here on
 * purpose — the graph is small and entirely process-scoped, so a hand-written container
 * is less machinery than Hilt's processing. See DECISIONS.md.
 */
class GalleryOrganizerApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * One shared image loader. The custom fetcher goes first so MediaStore's own
     * pre-generated thumbnails are used instead of decoding a 50 MB original for a 100 dp
     * cell.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(MediaStoreThumbnailFetcher.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache {
            // A grid fling touches a lot of bitmaps; a generous in-memory cache is what
            // makes scrolling back up instant.
            MemoryCache.Builder(this).maxSizePercent(0.25).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(THUMBNAIL_CACHE_BYTES)
                .build()
        }
        .build()

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 256L * 1024 * 1024
    }
}
