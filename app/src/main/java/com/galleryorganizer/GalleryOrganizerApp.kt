package com.galleryorganizer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.ui.image.MediaStoreThumbnailFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

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
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader {
        val disk = DiskCache.Builder()
            .directory(cacheDir.resolve("thumbnails"))
            .maxSizeBytes(THUMBNAIL_CACHE_BYTES)
            .build()

        return ImageLoader.Builder(this)
            .components {
                add(MediaStoreThumbnailFetcher.Factory { disk })
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                // A grid fling touches a lot of bitmaps; a generous in-memory cache is what
                // makes scrolling back up instant.
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache { disk }
            /*
             * Both of these default to `Dispatchers.IO`, which is up to sixty-four threads.
             * A hard fling through the grid can ask for that many thumbnails at once, and
             * when a thumbnail is not already cached the work behind it is a full JPEG
             * decode. Sixty-four concurrent decodes on eight cores saturate the CPU and
             * starve the main thread, which is felt as the *grid* stuttering even though
             * none of the work is on the main thread.
             *
             * Capping the pool keeps a few decodes in flight and leaves the rest of the
             * phone responsive. Nothing is lost by queueing: images the fling has already
             * flown past are cancelled before they are ever decoded.
             */
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(THUMBNAIL_PARALLELISM))
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(THUMBNAIL_PARALLELISM))
            .build()
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 256L * 1024 * 1024

        /**
         * Enough to keep the pipeline fed on a phone with eight-ish cores while leaving
         * headroom for rendering. The grid only shows a few dozen tiles at a time, so more
         * in flight buys nothing but contention.
         */
        const val THUMBNAIL_PARALLELISM = 4
    }
}
