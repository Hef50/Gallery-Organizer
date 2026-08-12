package com.galleryorganizer.data.repo

import android.net.Uri
import com.galleryorganizer.data.db.DbTest
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.db.entity.SuggestionStatus
import com.galleryorganizer.data.db.entity.TagSource
import com.galleryorganizer.data.ml.ImageAnalysis
import com.galleryorganizer.data.ml.ImageAnalyzer
import com.galleryorganizer.data.ml.SuggestedLabel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** ML Kit stands in as a map from URI to result, so the tagger's own rules are what is tested. */
private class FakeAnalyzer(
    var results: Map<String, ImageAnalysis?> = emptyMap(),
    var default: ImageAnalysis? = ImageAnalysis(),
) : ImageAnalyzer {
    var calls = 0
    override suspend fun analyze(uri: Uri, minConfidence: Float): ImageAnalysis? {
        calls++
        return if (results.containsKey(uri.toString())) results[uri.toString()] else default
    }
}

class AutoTaggerTest : DbTest() {

    private val analyzer = FakeAnalyzer()
    private val tagRepo by lazy { TagRepository(db, FtsMaintenance(db), now = { 5L }) }
    private val tagger by lazy {
        AutoTagger(db, analyzer, tagRepo, FtsMaintenance(db), now = { 5L })
    }

    private fun uriOf(mediaStoreId: Long) = "content://media/external/images/media/$mediaStoreId"

    @Test
    fun `labels become suggestions, never tags`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.results = mapOf(
            uriOf(1) to ImageAnalysis(
                labels = listOf(SuggestedLabel("Beach", 0.9f), SuggestedLabel("Sky", 0.8f)),
            ),
        )

        val result = tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(result.suggested).isEqualTo(2)
        // The point of the whole design: nothing appears as a tag the user did not ask for.
        assertThat(mediaTags.count()).isEqualTo(0)
        assertThat(db.suggestionDao().forMedia(id).map { it.label })
            .containsExactly("Beach", "Sky")
    }

    @Test
    fun `labels below the threshold are not even offered`() = runTest {
        media.insert(sampleMedia(1))
        analyzer.results = mapOf(
            uriOf(1) to ImageAnalysis(
                labels = listOf(SuggestedLabel("Beach", 0.95f), SuggestedLabel("Maybe", 0.42f)),
            ),
        )

        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(db.suggestionDao().count()).isEqualTo(1)
    }

    @Test
    fun `ocr text is written straight to the item and becomes searchable`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.results = mapOf(
            uriOf(1) to ImageAnalysis(text = "Shinkansen ticket Kyoto 2024"),
        )

        val result = tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        // Text visible in a photo is a fact about the file, not a guess, so it does not go
        // through review.
        assertThat(result.ocrWritten).isEqualTo(1)
        assertThat(media.byId(id)!!.ocrText).contains("Shinkansen")
        assertThat(fts.matchIds("shinkansen")).containsExactly(id)
    }

    @Test
    fun `an item is only ever analysed once`() = runTest {
        media.insertAll((1L..3L).map { sampleMedia(it) })

        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)
        val secondPass = tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(analyzer.calls).isEqualTo(3)
        assertThat(secondPass.analysed).isEqualTo(0)
        assertThat(tagger.remainingToAnalyse()).isEqualTo(0)
    }

    @Test
    fun `an unreadable file is marked failed rather than retried forever`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.default = null

        val result = tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(result.failed).isEqualTo(1)
        assertThat(media.byId(id)!!.autoScanState).isEqualTo(MediaEntity.AUTO_SCAN_FAILED)
        // The worker must not grind over the same broken file on every run.
        assertThat(tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f).failed).isEqualTo(0)
    }

    @Test
    fun `a photo with nothing recognisable is still marked as looked at`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis()

        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        // "We looked and found nothing" is different from "we have not looked yet".
        assertThat(media.byId(id)!!.autoScanState).isEqualTo(MediaEntity.AUTO_SCAN_DONE)
    }

    @Test
    fun `videos and missing items are skipped`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1),
                sampleMedia(2, isVideo = true),
                sampleMedia(3, isMissing = true),
            ),
        )

        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(analyzer.calls).isEqualTo(1)
    }

    @Test
    fun `the review queue groups by label so one decision covers many photos`() = runTest {
        val ids = media.insertAll((1L..5L).map { sampleMedia(it) })
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Beach", 0.9f)))
        analyzer.results = mapOf(
            uriOf(1) to ImageAnalysis(
                labels = listOf(SuggestedLabel("Beach", 0.9f), SuggestedLabel("Dog", 0.8f)),
            ),
        )
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        val groups = tagger.observePendingGroups().first()

        assertThat(groups.map { it.label to it.itemCount })
            .containsExactly("Beach" to 5, "Dog" to 1).inOrder()
        assertThat(ids).hasSize(5)
    }

    @Test
    fun `accepting a label tags every photo it was suggested for, with source auto`() = runTest {
        media.insertAll((1L..4L).map { sampleMedia(it) })
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Beach", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        val tagged = tagger.acceptLabel("Beach")

        assertThat(tagged).isEqualTo(4)
        assertThat(mediaTags.count()).isEqualTo(4)
        assertThat(mediaTags.allRows().map { it.source }.distinct())
            .containsExactly(TagSource.Auto)
        assertThat(tagger.observePendingGroups().first()).isEmpty()
    }

    @Test
    fun `accepting never downgrades a tag the user applied by hand`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1)))
        val beach = tagRepo.ensureTag("Beach")
        tagRepo.applyTags(ids, listOf(beach), TagSource.Manual)
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Beach", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        tagger.acceptLabel("Beach")

        assertThat(mediaTags.rowsFor(ids.first()).single().source).isEqualTo(TagSource.Manual)
    }

    @Test
    fun `accepting a label reuses an existing tag rather than creating a duplicate`() = runTest {
        media.insert(sampleMedia(1))
        val existing = tagRepo.ensureTag("Beach")
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("beach", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        tagger.acceptLabel("beach")

        assertThat(tags.count()).isEqualTo(1)
        assertThat(mediaTags.allRows().single().tagId).isEqualTo(existing)
    }

    @Test
    fun `a rejected label is remembered so it is never suggested again`() = runTest {
        media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Wrong", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        tagger.rejectLabel("Wrong")

        assertThat(tagger.observePendingGroups().first()).isEmpty()
        assertThat(mediaTags.count()).isEqualTo(0)
        // The rows are kept as rejected — deleting them is what would let the next scan
        // offer the same wrong guess again.
        assertThat(db.suggestionDao().count()).isEqualTo(1)
        assertThat(db.suggestionDao().forMedia(1).single().status)
            .isEqualTo(SuggestionStatus.Rejected)
    }

    @Test
    fun `re-analysing cannot resurrect a rejected suggestion`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Wrong", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)
        tagger.rejectLabel("Wrong")

        media.setAutoScanState(id, MediaEntity.AUTO_SCAN_PENDING)
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(tagger.observePendingGroups().first()).isEmpty()
    }

    @Test
    fun `forgetting rejections lets them be offered again after a threshold change`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Maybe", 0.75f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)
        tagger.rejectLabel("Maybe")

        assertThat(tagger.forgetRejections()).isEqualTo(1)
        media.setAutoScanState(id, MediaEntity.AUTO_SCAN_PENDING)
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(tagger.observePendingGroups().first().map { it.label }).containsExactly("Maybe")
    }

    @Test
    fun `accepting part of a label leaves the rest in the queue`() = runTest {
        val ids = media.insertAll((1L..4L).map { sampleMedia(it) })
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Beach", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        tagger.acceptLabelFor("Beach", ids.take(2))

        assertThat(mediaTags.count()).isEqualTo(2)
        assertThat(tagger.observePendingGroups().first().single().itemCount).isEqualTo(2)
    }

    @Test
    fun `duplicate labels from one photo are collapsed`() = runTest {
        media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis(
            labels = listOf(SuggestedLabel("Beach", 0.9f), SuggestedLabel("beach", 0.8f)),
        )

        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        assertThat(db.suggestionDao().count()).isEqualTo(1)
    }

    @Test
    fun `deleting an item takes its suggestions with it`() = runTest {
        val id = media.insert(sampleMedia(1))
        analyzer.default = ImageAnalysis(labels = listOf(SuggestedLabel("Beach", 0.9f)))
        tagger.analyseNextBatch(limit = 10, minConfidence = 0.7f)

        media.deleteRow(id)

        assertThat(db.suggestionDao().count()).isEqualTo(0)
    }
}
