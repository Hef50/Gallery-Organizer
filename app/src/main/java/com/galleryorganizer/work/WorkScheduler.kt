package com.galleryorganizer.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration

/** Every background job the app schedules, in one place. */
object WorkScheduler {

    const val INDEX_WORK = "media-index"
    const val INDEX_PERIODIC_WORK = "media-index-periodic"
    const val HASH_BACKFILL_WORK = "hash-backfill"
    const val AUTO_TAG_WORK = "auto-tag"

    /**
     * Catch-up pass, run on launch and after a permission change.
     *
     * `KEEP`, not `REPLACE`: replacing a running pass would cancel it mid-chunk and the
     * new one would only redo the same work. If one is already going, let it finish.
     */
    fun enqueueIndex(context: Context, sweepMissing: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            INDEX_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaIndexWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(MediaIndexWorker.KEY_SWEEP_MISSING, sweepMissing)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build(),
        )
    }

    /**
     * The daily pass, which also does the presence sweep. Deferred until the device is
     * charging and idle so a full id-only query of a 150k library never lands in the
     * middle of the user's day.
     */
    fun enqueuePeriodicIndex(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            INDEX_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaIndexWorker>(Duration.ofHours(24))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .setInputData(
                    Data.Builder()
                        .putBoolean(MediaIndexWorker.KEY_SWEEP_MISSING, true)
                        .build(),
                )
                .build(),
        )
    }

    /** Idle-time hashing of whatever the tag path has not already hashed on demand. */
    fun enqueueHashBackfill(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HASH_BACKFILL_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HashBackfillWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5))
                .build(),
        )
    }

    /** True while an indexing pass is queued or running, for the UI's progress strip. */
    fun observeIndexing(context: Context): Flow<IndexingStatus> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(INDEX_WORK)
            .map { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                IndexingStatus(
                    running = active?.state == WorkInfo.State.RUNNING,
                    queued = active != null,
                    scanned = active?.progress?.getInt(MediaIndexWorker.PROGRESS_SCANNED, 0) ?: 0,
                )
            }
}

data class IndexingStatus(
    val running: Boolean = false,
    val queued: Boolean = false,
    val scanned: Int = 0,
)
