package com.galleryorganizer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.galleryorganizer.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Runs on-device labelling and OCR over the library while the phone is charging and idle.
 *
 * Nothing it produces is applied on its own — labels land in a review queue. The only thing
 * it writes directly is OCR text, which is a fact about the file rather than a guess, and
 * which only ever makes search better.
 */
class AutoTagWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)
        if (!container.settings.autoTagEnabled.first()) return Result.success()

        val confidence = container.settings.autoTagConfidence.first()
        var analysed = 0
        var suggested = 0

        return try {
            while (analysed < MAX_PER_RUN) {
                currentCoroutineContext().ensureActive()
                val result = container.autoTagger.analyseNextBatch(BATCH, confidence)
                if (result.analysed == 0 && result.failed == 0) break
                analysed += result.analysed
                suggested += result.suggested
            }
            // Anything left over is picked up by the next idle window rather than
            // monopolising this worker's execution budget.
            if (container.autoTagger.remainingToAnalyse() > 0) {
                WorkScheduler.enqueueAutoTag(applicationContext)
            }
            Result.success(
                Data.Builder()
                    .putInt(RESULT_ANALYSED, analysed)
                    .putInt(RESULT_SUGGESTED, suggested)
                    .build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        } finally {
            container.closeAnalyzer()
        }
    }

    companion object {
        const val RESULT_ANALYSED = "analysed"
        const val RESULT_SUGGESTED = "suggested"
        private const val BATCH = 20
        private const val MAX_PER_RUN = 400
    }
}
