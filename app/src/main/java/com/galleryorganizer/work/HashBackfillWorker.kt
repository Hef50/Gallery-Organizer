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
 * Fills in `content_hash` for items that have not been hashed yet.
 *
 * The library is never hashed up front — that is minutes of I/O on 150k files. Instead an
 * item is hashed on its first tag write (where identity actually starts to matter), and
 * this worker grinds through the rest while the device is not busy, a slice at a time so
 * it can be stopped at any moment without losing progress.
 */
class HashBackfillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)
        var hashed = 0

        return try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val done = container.mediaRepository.hashNextBatch(BATCH)
                if (done == 0) break
                hashed += done
                if (hashed >= MAX_PER_RUN) {
                    // Hand the rest to a fresh run rather than monopolising the worker's
                    // execution window; WorkManager will schedule it again.
                    WorkScheduler.enqueueHashBackfill(applicationContext)
                    break
                }
            }
            Result.success(Data.Builder().putInt(RESULT_HASHED, hashed).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val RESULT_HASHED = "hashed"
        private const val BATCH = 200
        private const val MAX_PER_RUN = 5_000
    }
}
