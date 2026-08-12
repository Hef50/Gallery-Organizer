package com.galleryorganizer.data.db

import com.galleryorganizer.data.db.entity.IndexStateEntity
import com.galleryorganizer.data.db.entity.MediaFtsEntity
import com.galleryorganizer.data.db.entity.SavedSearchEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SchemaAndFtsTest : DbTest() {

    @Test
    fun `every table and index the query plans depend on exists`() {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertThat(tables).containsAtLeast(
            "media", "tag", "media_tag", "saved_search", "index_state", "media_fts",
        )

        val indices = db.query("SELECT name FROM sqlite_master WHERE type='index'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        // These are the difference between a 50 ms query and a full scan plus an in-memory
        // sort at 150k rows. QueryPlanTest checks the planner actually uses them.
        assertThat(indices).containsAtLeast(
            "index_media_is_missing_date_taken_id",
            "index_media_bucket_id_date_taken",
            "index_media_date_taken",
            "index_media_content_hash",
            "index_media_size_display_name",
            "index_media_tag_tag_id",
        )
    }

    @Test
    fun `foreign keys are actually enforced`() {
        // Room does not enable them by default; the AppDatabase callback does. Without it
        // the media_tag cascades would silently never fire.
        db.query("PRAGMA foreign_keys", null).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `fts matches filenames, tags and ocr text`() = runTest {
        val id = media.insert(sampleMedia(1, name = "IMG_20240612_kyoto.jpg"))
        fts.insertRows(
            listOf(
                MediaFtsEntity(
                    rowId = id,
                    displayName = "IMG_20240612_kyoto.jpg",
                    relativePath = "DCIM/Camera/",
                    tags = "Travel Japan Kyoto",
                    ocrText = "Shinkansen ticket Tokyo",
                ),
            ),
        )

        assertThat(fts.matchIds("kyoto")).containsExactly(id)
        assertThat(fts.matchIds("Japan")).containsExactly(id)
        assertThat(fts.matchIds("shinkansen")).containsExactly(id)
        assertThat(fts.matchIds("DCIM")).containsExactly(id)
        assertThat(fts.matchIds("paris")).isEmpty()
    }

    @Test
    fun `fts supports prefix matching, which is what as-you-type search needs`() = runTest {
        val id = media.insert(sampleMedia(1))
        fts.insertRows(
            listOf(MediaFtsEntity(id, "beach.jpg", "Pictures/", "Holiday Beach", "")),
        )
        assertThat(fts.matchIds("hol*")).containsExactly(id)
    }

    @Test
    fun `rebuilding an fts row replaces rather than duplicates it`() = runTest {
        val id = media.insert(sampleMedia(1))
        fts.insertRows(listOf(MediaFtsEntity(id, "a.jpg", "", "Travel", "")))
        fts.deleteRows(listOf(id))
        fts.insertRows(listOf(MediaFtsEntity(id, "a.jpg", "", "Travel Japan", "")))

        assertThat(fts.count()).isEqualTo(1)
        assertThat(fts.matchIds("Japan")).containsExactly(id)
    }

    @Test
    fun `index state round-trips and is observable`() = runTest {
        indexState.put(IndexStateKeys.IMAGE_WATERMARK, "1700000000")
        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("1700000000")

        indexState.put(IndexStateEntity(IndexStateKeys.IMAGE_WATERMARK, "1800000000"))
        assertThat(indexState.observe(IndexStateKeys.IMAGE_WATERMARK).first())
            .isEqualTo("1800000000")

        indexState.remove(IndexStateKeys.IMAGE_WATERMARK)
        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isNull()
    }

    @Test
    fun `saved searches sort pinned first`() = runTest {
        savedSearches.insert(SavedSearchEntity(name = "Zebras", queryJson = "{}", sortOrder = 1))
        savedSearches.insert(
            SavedSearchEntity(name = "Aardvarks", queryJson = "{}", pinned = true, sortOrder = 9),
        )

        assertThat(savedSearches.observeAll().first().map { it.name })
            .containsExactly("Aardvarks", "Zebras").inOrder()
    }

    @Test
    fun `the database reports the version the migrations array is written against`() {
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
        assertThat(ALL_MIGRATIONS.map { it.startVersion to it.endVersion })
            .containsNoDuplicates()
    }
}
