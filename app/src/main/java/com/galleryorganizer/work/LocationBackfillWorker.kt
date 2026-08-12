package com.galleryorganizer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.galleryorganizer.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads EXIF coordinates for photos that have not been looked at yet.
 *
 * Separate from the indexer because it is a *second* full read of every file — the indexer
 * gets its metadata from MediaStore's index without opening anything, while this has to
 * open the original bytes. Doing it in the same pass would roughly double the cost of the
 * first index for a feature most users will not open on day one.
 */
class LocationBackfillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)
        var read = 0

        return try {
            while (read < MAX_PER_RUN) {
                currentCoroutineContext().ensureActive()
                val done = container.locationRepository.readNextBatch(BATCH)
                if (done == 0) break
                read += done
            }
            if (container.locationRepository.remaining() > 0) {
                WorkScheduler.enqueueLocationBackfill(
                    applicationContext,
                    WorkScheduler.LOCATION_STEP_DELAY,
                )
            }
            Result.success(Data.Builder().putInt(RESULT_READ, read).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val RESULT_READ = "read"

        /**
         * Rows per transaction, matching the app's ~500-row convention.
         *
         * Larger than it needs to be for throughput, and that is the point: every commit to
         * `media` invalidates the grid's paging source, which makes the grid re-query from
         * wherever the user is scrolling. Room's invalidation is per table, so there is no
         * way to say "this write does not change anything the grid shows" — the only lever
         * is committing less often. At 250 a 150k library interrupted the grid six hundred
         * times.
         */
        private const val BATCH = 500
        private const val MAX_PER_RUN = 6_000
    }
}
