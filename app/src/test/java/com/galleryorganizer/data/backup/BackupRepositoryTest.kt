package com.galleryorganizer.data.backup

import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.DbTest
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.data.db.entity.TagSource
import com.galleryorganizer.data.repo.AlbumRepository
import com.galleryorganizer.data.repo.FtsMaintenance
import com.galleryorganizer.data.repo.SearchRepository
import com.galleryorganizer.data.repo.TagRepository
import com.galleryorganizer.domain.search.SearchQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupRepositoryTest : DbTest() {

    private val tagRepo by lazy { TagRepository(db, FtsMaintenance(db), now = { 7L }) }
    private val albumRepo by lazy { AlbumRepository(db) { 7L } }
    private val backup by lazy {
        BackupRepository(db, tagRepo, FtsMaintenance(db), now = { 7L }, albums = albumRepo)
    }

    private suspend fun exportToString(): String {
        val out = ByteArrayOutputStream()
        backup.export(out)
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend fun importFrom(text: String): ImportReport =
        backup.import(ByteArrayInputStream(text.toByteArray()))

    /** A second, independent database, standing in for "a new phone". */
    private fun freshDatabase(): AppDatabase =
        androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

    @Test
    fun `the export is one json object per line, starting with a header`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensurePath(listOf("Travel", "Japan"))))

        val lines = exportToString().trim().lines()

        assertThat(lines.first()).contains("\"type\":\"header\"")
        assertThat(lines.first()).contains(BackupHeader.FORMAT)
        // Every line has to stand alone — that is what makes the format streamable and
        // what makes a truncated file still partly restorable.
        lines.forEach { assertThat(it).startsWith("{") }
        assertThat(lines.count { it.contains("\"type\":\"tag\"") }).isEqualTo(2)
        assertThat(lines.count { it.contains("\"type\":\"item\"") }).isEqualTo(1)
    }

    @Test
    fun `only tagged items are exported`() = runTest {
        val ids = media.insertAll((1L..10L).map { sampleMedia(it) })
        tagRepo.applyTags(ids.take(3), listOf(tagRepo.ensureTag("Keep")))

        val stats = ByteArrayOutputStream().let { out -> backup.export(out) }

        // The other seven rows are pure MediaStore facts that reindexing rebuilds.
        assertThat(stats.items).isEqualTo(3)
    }

    @Test
    fun `tags survive a reinstall and are matched back by content hash`() = runTest {
        val ids = media.insertAll(
            listOf(sampleMedia(1, contentHash = "h1"), sampleMedia(2, contentHash = "h2")),
        )
        val kyoto = tagRepo.ensurePath(listOf("Travel", "Japan", "Kyoto"))
        tagRepo.applyTags(ids, listOf(kyoto))
        val exported = exportToString()

        // The "new phone": same files, different database ids, different MediaStore ids.
        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(fresh, freshTags, FtsMaintenance(fresh), now = { 7L })
            fresh.mediaDao().insertAll(
                listOf(
                    sampleMedia(900, contentHash = "h1"),
                    sampleMedia(901, contentHash = "h2"),
                ),
            )

            val report = freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(report.itemsMatchedByHash).isEqualTo(2)
            assertThat(report.itemsUnmatched).isEqualTo(0)
            assertThat(fresh.mediaTagDao().count()).isEqualTo(2)
            val restored = fresh.tagDao().allTags().map { it.name }
            assertThat(restored).containsExactly("Travel", "Japan", "Kyoto")
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `restore works on a fresh install where nothing has been hashed yet`() = runTest {
        // This is the case that matters most and the one a hash-only restore would fail:
        // hashing is lazy, so a freshly indexed library has no hashes at all.
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1", size = 4242)))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(fresh, freshTags, FtsMaintenance(fresh), now = { 7L })
            fresh.mediaDao().insert(
                sampleMedia(900, name = "IMG_1.jpg", contentHash = null, size = 4242),
            )

            val report = freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(report.itemsMatchedByName).isEqualTo(1)
            assertThat(fresh.mediaTagDao().count()).isEqualTo(1)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a hash shared by two copies tags both of them`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "dup")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        val exported = exportToString()

        // A second copy of the same photo has appeared since the backup was taken.
        media.insert(sampleMedia(2, name = "IMG_1 (copy).jpg", contentHash = "dup"))
        db.mediaTagDao().clearTagsFor(ids)

        importFrom(exported)

        assertThat(mediaTags.count()).isEqualTo(2)
    }

    @Test
    fun `restoring never deletes or downgrades what is already there`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        val travel = tagRepo.ensureTag("Travel")
        tagRepo.applyTags(ids, listOf(travel))
        val exported = exportToString()

        // Since the backup, the user added another tag by hand.
        val food = tagRepo.ensureTag("Food")
        tagRepo.applyTags(ids, listOf(food))

        importFrom(exported)

        assertThat(mediaTags.tagsFor(ids.first()).map { it.name })
            .containsExactly("Travel", "Food")
        // The manual tag keeps its source rather than being restamped as imported.
        assertThat(mediaTags.rowsFor(ids.first()).map { it.source })
            .containsExactly(TagSource.Manual, TagSource.Manual)
    }

    @Test
    fun `importing into a database that already has the tags merges rather than duplicates`() =
        runTest {
            val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
            tagRepo.applyTags(ids, listOf(tagRepo.ensurePath(listOf("Travel", "Japan"))))
            val exported = exportToString()

            val report = importFrom(exported)

            assertThat(report.tagsMerged).isEqualTo(2)
            assertThat(report.tagsCreated).isEqualTo(0)
            assertThat(tags.count()).isEqualTo(2)
        }

    @Test
    fun `an item the phone does not have is reported rather than silently dropped`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(fresh, freshTags, FtsMaintenance(fresh), now = { 7L })

            val report = freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(report.itemsUnmatched).isEqualTo(1)
            assertThat(report.itemsMatched).isEqualTo(0)
            // The tag hierarchy is still restored, so the structure is ready for when the
            // photos come back.
            assertThat(fresh.tagDao().count()).isEqualTo(1)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a truncated backup still restores everything up to the cut`() = runTest {
        val ids = media.insertAll((1L..5L).map { sampleMedia(it, contentHash = "h$it") })
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        val exported = exportToString()
        // A full disk or a cancelled write leaves a file ending mid-line.
        val truncated = exported.substring(0, exported.length - 40)

        db.mediaTagDao().clearTagsFor(ids)
        val report = importFrom(truncated)

        assertThat(report.itemsMatched).isAtLeast(3)
        assertThat(report.wrongFormat).isFalse()
        assertThat(mediaTags.count()).isAtLeast(3)
    }

    @Test
    fun `a corrupt line costs that line and nothing else`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1"), sampleMedia(2, contentHash = "h2")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        val lines = exportToString().trim().lines().toMutableList()
        lines.add(2, "{ this is not json")

        db.mediaTagDao().clearTagsFor(ids)
        val report = importFrom(lines.joinToString("\n"))

        assertThat(report.malformedLines).isEqualTo(1)
        assertThat(report.itemsMatched).isEqualTo(2)
    }

    @Test
    fun `a file that is not a backup is rejected instead of half-applied`() = runTest {
        val report = importFrom("""{"hello":"world"}""")
        assertThat(report.wrongFormat).isTrue()
        assertThat(tags.count()).isEqualTo(0)
    }

    @Test
    fun `ocr text is restored but never overwrites text already present`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1", ocrText = "ticket")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Receipts")))
        val exported = exportToString()

        media.setOcrText(ids.first(), null)
        assertThat(importFrom(exported).ocrRestored).isEqualTo(1)
        assertThat(media.byId(ids.first())!!.ocrText).isEqualTo("ticket")

        // A second restore must not clobber what is now there.
        assertThat(importFrom(exported).ocrRestored).isEqualTo(0)
    }

    @Test
    fun `restored items are immediately searchable`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensurePath(listOf("Travel", "Japan"))))
        val exported = exportToString()

        db.mediaTagDao().clearTagsFor(ids)
        FtsMaintenance(db).rebuild(ids)
        assertThat(fts.matchIds("Japan")).isEmpty()

        importFrom(exported)

        assertThat(fts.matchIds("Japan")).containsExactly(ids.first())
        assertThat(fts.matchIds("Travel")).containsExactly(ids.first())
    }

    @Test
    fun `saved searches come back with their tag ids remapped to this device`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        val kyoto = tagRepo.ensurePath(listOf("Travel", "Japan", "Kyoto"))
        tagRepo.applyTags(ids, listOf(kyoto))
        SearchRepository(db, now = { 7L }).save(
            "Japan trip",
            SearchQuery(text = "kyoto", allTags = listOf(kyoto), bucketIds = listOf(99)),
        )
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(fresh, freshTags, FtsMaintenance(fresh), now = { 7L })
            fresh.mediaDao().insert(sampleMedia(900, contentHash = "h1"))
            // A tag that already exists here, so local ids are guaranteed to differ.
            freshTags.ensureTag("Something else")

            freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            val restored = SearchRepository(fresh, now = { 7L }).all().single()
            val localKyoto = fresh.tagDao().allTags().single { it.name == "Kyoto" }.id
            assertThat(restored.query.allTags).containsExactly(localKyoto)
            assertThat(restored.query.text).isEqualTo("kyoto")
            // Folder ids are MediaStore's and mean nothing on a different device.
            assertThat(restored.query.bucketIds).isEmpty()
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a saved search whose name is taken is skipped, not duplicated`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        tagRepo.applyTags(ids, listOf(tagRepo.ensureTag("Travel")))
        SearchRepository(db, now = { 7L }).save("Japan trip", SearchQuery(text = "kyoto"))
        val exported = exportToString()

        val report = importFrom(exported)

        assertThat(report.savedSearchesSkipped).isEqualTo(1)
        assertThat(savedSearches.count()).isEqualTo(1)
    }

    @Test
    fun `export and import are stable across a large library`() = runTest {
        val ids = media.insertAll((1L..1_500L).map { sampleMedia(it, contentHash = "h$it") })
        val tag = tagRepo.ensureTag("Bulk")
        tagRepo.applyTags(ids, listOf(tag))
        val exported = exportToString()

        db.mediaTagDao().clearTagsFor(ids)
        val report = importFrom(exported)

        assertThat(report.itemsMatched).isEqualTo(1_500)
        assertThat(mediaTags.count()).isEqualTo(1_500)
    }

    // --- Albums and tag kinds (schema v5) -----------------------------------------------

    @Test
    fun `an album survives a reinstall with its order and its cover`() = runTest {
        val ids = media.insertAll(
            (1L..4L).map { sampleMedia(it, contentHash = "h$it") },
        )
        val albumId = albumRepo.ensureAlbum("Japan 2019", "The good ones")
        // Deliberately not in id order: the album's order is the thing being tested.
        albumRepo.addTo(albumId, listOf(ids[2], ids[0], ids[3]))
        albumRepo.setCover(albumId, ids[3])
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshAlbums = com.galleryorganizer.data.repo.AlbumRepository(fresh) { 7L }
            val freshBackup = BackupRepository(
                fresh,
                freshTags,
                FtsMaintenance(fresh),
                now = { 7L },
                albums = freshAlbums,
            )
            // Same files, different ids — and inserted in a different order again.
            val freshIds = fresh.mediaDao().insertAll(
                listOf(
                    sampleMedia(904, contentHash = "h4"),
                    sampleMedia(901, contentHash = "h1"),
                    sampleMedia(903, contentHash = "h3"),
                    sampleMedia(902, contentHash = "h2"),
                ),
            )

            val report = freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(report.albumsCreated).isEqualTo(1)
            assertThat(report.albumMembershipsApplied).isEqualTo(3)

            val restored = fresh.albumDao().byName("Japan 2019")!!
            assertThat(restored.description).isEqualTo("The good ones")

            // h3, h1, h4 — the exported order, not the insertion order.
            val byHash = fresh.albumDao().mediaIdsIn(restored.id).map { id ->
                fresh.mediaDao().byId(id)!!.contentHash
            }
            assertThat(byHash).containsExactly("h3", "h1", "h4").inOrder()

            // The cover pointed at h4, which is a different row id on this device.
            assertThat(restored.coverMediaId).isEqualTo(freshIds[0])
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `an untagged photo is exported because an album is holding it`() = runTest {
        val ids = media.insertAll((1L..3L).map { sampleMedia(it, contentHash = "h$it") })
        val albumId = albumRepo.ensureAlbum("Untagged but kept")
        albumRepo.addTo(albumId, listOf(ids[0]))

        val stats = ByteArrayOutputStream().let { out -> backup.export(out) }

        // Album membership is hand-made data too — losing it would be as bad as losing tags.
        assertThat(stats.items).isEqualTo(1)
        assertThat(stats.albums).isEqualTo(1)
        assertThat(stats.albumMemberships).isEqualTo(1)
    }

    @Test
    fun `importing into a database that already has the album merges rather than duplicates`() =
        runTest {
            val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
            val albumId = albumRepo.ensureAlbum("Holiday")
            albumRepo.addTo(albumId, ids)
            val exported = exportToString()

            val report = importFrom(exported)

            assertThat(report.albumsMerged).isEqualTo(1)
            assertThat(report.albumsCreated).isEqualTo(0)
            assertThat(db.albumDao().count()).isEqualTo(1)
            // Re-importing must not put the same photo in twice.
            assertThat(db.albumDao().mediaIdsIn(albumId)).containsExactly(ids.single())
        }

    @Test
    fun `an album whose cover is not on this phone still restores`() = runTest {
        val ids = media.insertAll(
            listOf(sampleMedia(1, contentHash = "h1"), sampleMedia(2, contentHash = "h2")),
        )
        val albumId = albumRepo.ensureAlbum("Half here")
        albumRepo.addTo(albumId, ids)
        albumRepo.setCover(albumId, ids[1])
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(
                fresh,
                freshTags,
                FtsMaintenance(fresh),
                now = { 7L },
                albums = com.galleryorganizer.data.repo.AlbumRepository(fresh) { 7L },
            )
            // Only the first photo made it across.
            fresh.mediaDao().insertAll(listOf(sampleMedia(901, contentHash = "h1")))

            val report = freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(report.albumsCreated).isEqualTo(1)
            assertThat(report.itemsUnmatched).isEqualTo(1)
            val restored = fresh.albumDao().byName("Half here")!!
            // No cover rather than a dangling one; the shelf falls back to the first member.
            assertThat(restored.coverMediaId).isNull()
            assertThat(fresh.albumDao().mediaIdsIn(restored.id)).hasSize(1)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `tag kinds travel with the backup`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        val anna = tagRepo.ensureTag("Anna", kind = TagKind.Person)
        val kyoto = tagRepo.ensureTag("Kyoto", kind = TagKind.Place)
        tagRepo.applyTags(ids, listOf(anna, kyoto))
        val exported = exportToString()

        val fresh = freshDatabase()
        try {
            val freshTags = TagRepository(fresh, FtsMaintenance(fresh), now = { 7L })
            val freshBackup = BackupRepository(fresh, freshTags, FtsMaintenance(fresh), now = { 7L })
            fresh.mediaDao().insertAll(listOf(sampleMedia(901, contentHash = "h1")))

            freshBackup.import(ByteArrayInputStream(exported.toByteArray()))

            assertThat(fresh.tagDao().byNameUnder(0, "Anna")!!.kind).isEqualTo(TagKind.Person)
            assertThat(fresh.tagDao().byNameUnder(0, "Kyoto")!!.kind).isEqualTo(TagKind.Place)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `restoring an older backup does not reset a kind chosen on this device`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1, contentHash = "h1")))
        val anna = tagRepo.ensureTag("Anna")
        tagRepo.applyTags(ids, listOf(anna))
        // A v1-era file: every tag record says "note", because kinds did not exist.
        val exported = exportToString()

        tagRepo.setKind(anna, TagKind.Person)
        importFrom(exported)

        assertThat(db.tagDao().byId(anna)!!.kind).isEqualTo(TagKind.Person)
    }
}
