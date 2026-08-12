package com.galleryorganizer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.galleryorganizer.data.repo.MediaIndexer
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.permissions.MediaPermissions
import kotlinx.coroutines.CancellationException

/**
 * Runs one indexing pass.
 *
 * The worker itself is deliberately thin — all the interesting logic lives in
 * [MediaIndexer], which takes a [com.galleryorganizer.data.media.MediaStoreSource] and is
 * therefore testable on the JVM against a fake ContentProvider.
 */
class MediaIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)

        // Permission can be revoked between scheduling and running. Failing loudly here
        // would just retry forever, so this is a quiet success — the UI already shows the
        // permission screen.
        if (!MediaPermissions.state(applicationContext).canIndex) {
            return Result.success(outcome(skipped = true))
        }

        val notifier = IndexNotifier(applicationContext)
        notifier.ensureChannel()
        notifier.showProgress(scanned = 0)

        return try {
            val result = container.mediaIndexer.runPass(
                sweepMissing = inputData.getBoolean(KEY_SWEEP_MISSING, true),
            ) { progress ->
                notifier.showProgress(progress.scanned)
                setProgress(
                    Data.Builder()
                        .putInt(PROGRESS_SCANNED, progress.scanned)
                        .putInt(PROGRESS_INSERTED, progress.inserted)
                        .build(),
                )
            }

            // Newly indexed items are worth hashing while the device is idle so the first
            // tag write does not have to wait on I/O.
            if (result.inserted > 0) {
                WorkScheduler.enqueueHashBackfill(applicationContext)
            }

            Result.success(
                Data.Builder()
                    .putInt(RESULT_INSERTED, result.inserted)
                    .putInt(RESULT_UPDATED, result.updated)
                    .putInt(RESULT_MISSING, result.markedMissing)
                    .putInt(RESULT_RESTORED, result.restored)
                    .build(),
            )
        } catch (e: CancellationException) {
            // Stopped mid-pass. Everything committed so far is durable and the watermark
            // points at it, so the retry resumes rather than restarting.
            throw e
        } catch (e: Exception) {
            Result.retry()
        } finally {
            notifier.clear()
        }
    }

    private fun outcome(skipped: Boolean) =
        Data.Builder().putBoolean(RESULT_SKIPPED, skipped).build()

    companion object {
        const val KEY_SWEEP_MISSING = "sweep_missing"

        const val PROGRESS_SCANNED = "scanned"
        const val PROGRESS_INSERTED = "inserted"

        const val RESULT_INSERTED = "inserted"
        const val RESULT_UPDATED = "updated"
        const val RESULT_MISSING = "missing"
        const val RESULT_RESTORED = "restored"
        const val RESULT_SKIPPED = "skipped"
    }
}
