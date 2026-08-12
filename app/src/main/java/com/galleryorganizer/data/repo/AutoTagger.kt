package com.galleryorganizer.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.dao.SuggestionGroup
import com.galleryorganizer.data.db.entity.LabelSuggestionEntity
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.db.entity.SuggestionStatus
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagSource
import com.galleryorganizer.data.ml.ImageAnalyzer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

data class AutoTagResult(
    val analysed: Int = 0,
    val suggested: Int = 0,
    val ocrWritten: Int = 0,
    val failed: Int = 0,
)

/**
 * On-device analysis: ML Kit labels become *suggestions* the user reviews, and OCR text
 * goes straight into the search index.
 *
 * Nothing here ever writes a tag the user has not agreed to. The distinction matters:
 * "Beach" is a guess and belongs in a review queue, whereas the text visible in a
 * photograph is a fact about the file and belongs in `media.ocr_text` where search can
 * find it. When a suggestion *is* accepted it is written with `source = auto`, and every
 * insert is IGNORE, so an existing manual tag is never downgraded.
 */
class AutoTagger(
    private val db: AppDatabase,
    private val analyzer: ImageAnalyzer,
    private val tags: TagRepository = TagRepository(db),
    private val fts: FtsMaintenance = FtsMaintenance(db),
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observePendingGroups(): Flow<List<SuggestionGroup>> =
        db.suggestionDao().observePendingGroups()

    fun observePendingCount(): Flow<Int> = db.suggestionDao().observePendingCount()

    /**
     * Analyses up to [limit] items that have not been looked at yet.
     *
     * Cancellation is checked per item and each item commits on its own, so being stopped
     * costs at most one photo's work.
     */
    suspend fun analyseNextBatch(limit: Int, minConfidence: Float): AutoTagResult {
        var result = AutoTagResult()
        val targets = db.mediaDao().unanalysedItems(limit)

        for (item in targets) {
            currentCoroutineContext().ensureActive()
            result += analyseOne(item, minConfidence)
        }
        return result
    }

    private suspend fun analyseOne(item: MediaEntity, minConfidence: Float): AutoTagResult {
        val analysis = runCatchingCancellable {
            analyzer.analyze(Uri.parse(item.uri), minConfidence)
        }.getOrNull()

        if (analysis == null) {
            // Marked failed rather than left pending, or the worker would grind over the
            // same unreadable file on every run forever.
            db.mediaDao().setAutoScanState(item.id, MediaEntity.AUTO_SCAN_FAILED)
            return AutoTagResult(failed = 1)
        }

        val suggestions = analysis.labels
            .filter { it.confidence >= minConfidence }
            .distinctBy { it.label.lowercase() }
            .map {
                LabelSuggestionEntity(
                    mediaId = item.id,
                    label = it.label,
                    confidence = it.confidence,
                    status = SuggestionStatus.Pending,
                    createdAt = now(),
                )
            }

        val ocr = analysis.text?.takeIf { it.isNotBlank() }

        db.withTransaction {
            if (suggestions.isNotEmpty()) db.suggestionDao().insertIgnoring(suggestions)
            if (ocr != null) {
                db.mediaDao().setOcrText(item.id, ocr)
                fts.rebuild(listOf(item.id))
            }
            db.mediaDao().setAutoScanState(item.id, MediaEntity.AUTO_SCAN_DONE)
        }

        return AutoTagResult(
            analysed = 1,
            suggested = suggestions.size,
            ocrWritten = if (ocr != null) 1 else 0,
        )
    }

    /**
     * Turns every pending suggestion of [label] into a real tag.
     *
     * Reviewing by label rather than by item is the only thing that makes this usable:
     * "Beach — 340 photos, accept?" is one decision where item-by-item would be 340.
     */
    suspend fun acceptLabel(label: String, parentTagId: Long = TagEntity.ROOT_PARENT_ID): Int {
        val mediaIds = db.suggestionDao().pendingMediaFor(label)
        if (mediaIds.isEmpty()) return 0
        val tagId = tags.ensureTag(label, parentTagId)
        // source = auto, inserted with IGNORE, so a manual tag of the same name on any of
        // these items keeps its provenance.
        tags.applyTags(mediaIds, listOf(tagId), TagSource.Auto)
        db.suggestionDao().setStatusForLabel(label, SuggestionStatus.Accepted)
        return mediaIds.size
    }

    /**
     * Rejects a label. The rows are kept as `rejected` rather than deleted — that is the
     * only way a later scan can avoid offering the same wrong guess again.
     */
    suspend fun rejectLabel(label: String): Int {
        val count = db.suggestionDao().pendingMediaFor(label).size
        db.suggestionDao().setStatusForLabel(label, SuggestionStatus.Rejected)
        return count
    }

    /** Accepts a label for part of its suggestions only. */
    suspend fun acceptLabelFor(
        label: String,
        mediaIds: List<Long>,
        parentTagId: Long = TagEntity.ROOT_PARENT_ID,
    ): Int {
        if (mediaIds.isEmpty()) return 0
        val tagId = tags.ensureTag(label, parentTagId)
        tags.applyTags(mediaIds, listOf(tagId), TagSource.Auto)
        db.suggestionDao().setStatusFor(label, mediaIds, SuggestionStatus.Accepted)
        return mediaIds.size
    }

    suspend fun mediaForLabel(label: String): List<Long> = db.suggestionDao().pendingMediaFor(label)

    suspend fun remainingToAnalyse(): Int = db.mediaDao().unanalysedCount()

    /** Lets previously rejected labels be offered again after a model or threshold change. */
    suspend fun forgetRejections(): Int = db.suggestionDao().clearRejected()

    private operator fun AutoTagResult.plus(other: AutoTagResult) = AutoTagResult(
        analysed = analysed + other.analysed,
        suggested = suggested + other.suggested,
        ocrWritten = ocrWritten + other.ocrWritten,
        failed = failed + other.failed,
    )
}
