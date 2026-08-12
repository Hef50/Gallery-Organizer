package com.galleryorganizer.data.backup

import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.entity.AlbumMediaCrossRef
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.SavedSearchEntity
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.data.db.entity.TagSource
import com.galleryorganizer.data.repo.AlbumRepository
import com.galleryorganizer.data.repo.FtsMaintenance
import com.galleryorganizer.data.repo.TagRepository
import com.galleryorganizer.domain.search.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and restore of everything the app owns and the device cannot regenerate: the tag
 * hierarchy, which items carry which tags, OCR text, and saved searches.
 *
 * Media is never exported — the photos are already on the device and are not this app's to
 * copy. What is exported is the *identity* of each tagged item (content hash, plus size
 * and filename as a fallback) so tags can be reattached after a reinstall, a file move, or
 * a move to a different phone.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val tags: TagRepository,
    private val fts: FtsMaintenance = FtsMaintenance(db),
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val albums: AlbumRepository = AlbumRepository(db, now),
) {

    // --- Export -----------------------------------------------------------------------

    /**
     * Writes the backup to [sink] as JSON Lines, a page at a time, so memory stays flat
     * regardless of library size.
     *
     * Only items carrying at least one tag are written. The other 149,000 rows are pure
     * MediaStore facts that reindexing rebuilds in minutes; including them would inflate
     * the file by two orders of magnitude and protect nothing.
     */
    suspend fun export(
        sink: OutputStream,
        appVersion: String = "",
        onProgress: suspend (written: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupStats = withContext(io) {
        val writer = sink.bufferedWriter()
        var stats = BackupStats()

        val allTags = db.tagDao().allTags()
        val allAlbums = db.albumDao().allAlbums()
        val total = db.mediaDao().backupCount()

        writer.writeRecord(
            BackupHeader(
                exportedAt = now(),
                appVersion = appVersion,
                tagCount = allTags.size,
                itemCount = total,
                savedSearchCount = db.savedSearchDao().count(),
                albumCount = allAlbums.size,
            ),
        )

        val paths = tagPaths(allTags)
        for (tag in allTags) {
            writer.writeRecord(
                TagRecord(
                    ref = tag.id,
                    path = paths.getValue(tag.id),
                    color = tag.color,
                    kind = tag.kind.wire,
                ),
            )
        }
        stats = stats.copy(tags = allTags.size)

        // Albums go out before items so each item can carry its own membership and the file
        // still streams. The cover is a forward reference to an item ref — see AlbumRecord.
        for (album in allAlbums) {
            writer.writeRecord(
                AlbumRecord(
                    ref = album.id,
                    name = album.name,
                    description = album.description,
                    coverRef = album.coverMediaId,
                ),
            )
        }
        stats = stats.copy(albums = allAlbums.size)

        var offset = 0
        var written = 0
        var assignments = 0
        var memberships = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = db.mediaDao().backupIdsPage(PAGE, offset)
            if (ids.isEmpty()) break

            val rows = db.mediaDao().byIds(ids).associateBy { it.id }
            val assignmentsByMedia = db.mediaTagDao().rowsForMany(ids).groupBy { it.mediaId }
            val albumsByMedia = db.albumDao().membershipsForMany(ids).groupBy { it.mediaId }

            for (id in ids) {
                val media = rows[id] ?: continue
                writer.writeRecord(
                    ItemRecord(
                        hash = media.contentHash,
                        displayName = media.displayName,
                        relativePath = media.relativePath,
                        size = media.size,
                        dateTaken = media.dateTaken,
                        ocrText = media.ocrText,
                        // The local row id doubles as the file-local ref. It is meaningless
                        // on the importing device, which is exactly why it is only ever used
                        // to join records inside one file.
                        ref = media.id,
                        tags = assignmentsByMedia[id].orEmpty().map {
                            TagAssignment(it.tagId, it.source.wire, it.createdAt)
                        },
                        albums = albumsByMedia[id].orEmpty().map {
                            AlbumMembership(it.albumId, it.position)
                        },
                    ),
                )
                assignments += assignmentsByMedia[id]?.size ?: 0
                memberships += albumsByMedia[id]?.size ?: 0
                written++
            }
            offset += ids.size
            onProgress(written, total)
            if (ids.size < PAGE) break
        }
        stats = stats.copy(
            items = written,
            assignments = assignments,
            albumMemberships = memberships,
        )

        val searches = db.savedSearchDao().all()
        for (saved in searches) {
            writer.writeRecord(SavedSearchRecord(saved.name, saved.queryJson, saved.pinned))
        }
        writer.flush()
        stats.copy(savedSearches = searches.size)
    }

    /** Full `Travel/Japan/Kyoto` path per tag id, so the file carries no local ids. */
    private fun tagPaths(all: List<TagEntity>): Map<Long, List<String>> {
        val byId = all.associateBy { it.id }
        return all.associate { tag ->
            val path = ArrayDeque<String>()
            var node: TagEntity? = tag
            var guard = 0
            while (node != null && guard++ < MAX_DEPTH) {
                path.addFirst(node.name)
                node = byId[node.parentId]
            }
            tag.id to path.toList()
        }
    }

    private fun BufferedWriter.writeRecord(record: BackupRecord) {
        write(BackupJson.encodeToString(BackupRecord.serializer(), record))
        newLine()
    }

    // --- Import -----------------------------------------------------------------------

    /**
     * Restores from [source]. Purely additive: nothing existing is ever deleted or
     * overwritten, tags merge by path, and assignments are inserted with IGNORE so a tag
     * the user has since applied manually keeps its `manual` source.
     *
     * Matching is deliberately two-tier. The content hash is the strong key, but on a
     * fresh install almost nothing has been hashed yet — hashing is lazy — so a
     * hash-only restore would match nothing on the one occasion it matters most. Size plus
     * filename is the fallback, and it is why this works the day the phone is replaced.
     */
    suspend fun import(
        source: InputStream,
        onProgress: suspend (read: Int) -> Unit = {},
    ): ImportReport = withContext(io) {
        var report = ImportReport()
        val tagIdByRef = HashMap<Long, Long>()
        val albumIdByRef = HashMap<Long, Long>()
        /** albumRef → the item ref it wants as a cover, resolved as items stream past. */
        val pendingCovers = HashMap<Long, Long>()
        var sawHeader = false
        var read = 0

        source.bufferedReader().useLines { lines ->
            for (line in lines) {
                currentCoroutineContext().ensureActive()
                if (line.isBlank()) continue

                val record = runCatching {
                    BackupJson.decodeFromString(BackupRecord.serializer(), line)
                }.getOrNull()

                if (record == null) {
                    // One corrupt line must not cost the user the rest of the file.
                    report = report.copy(malformedLines = report.malformedLines + 1)
                    continue
                }

                when (record) {
                    is BackupHeader -> {
                        sawHeader = true
                        if (record.format != BackupHeader.FORMAT) {
                            return@withContext report.copy(wrongFormat = true)
                        }
                    }

                    is TagRecord -> {
                        val existing = resolvePath(record.path)
                        val id = tags.ensurePath(record.path)
                        tagIdByRef[record.ref] = id
                        report = if (existing != null) {
                            report.copy(tagsMerged = report.tagsMerged + 1)
                        } else {
                            report.copy(tagsCreated = report.tagsCreated + 1)
                        }
                        record.color?.let { tags.setColor(id, it) }
                        // Only kind a tag this file actually classified. Re-kinding an
                        // existing tag from a v1 file's implicit "note" would undo work the
                        // user did on this device.
                        if (existing == null && record.kind != TagKind.Note.wire) {
                            tags.setKind(id, TagKind.fromWire(record.kind))
                        }
                    }

                    is AlbumRecord -> {
                        val existing = db.albumDao().byName(record.name.trim())
                        val id = albums.ensureAlbum(record.name, record.description)
                        albumIdByRef[record.ref] = id
                        report = if (existing != null) {
                            report.copy(albumsMerged = report.albumsMerged + 1)
                        } else {
                            report.copy(albumsCreated = report.albumsCreated + 1)
                        }
                        record.coverRef?.let { pendingCovers[record.ref] = it }
                    }

                    is ItemRecord -> {
                        report = applyItem(record, tagIdByRef, albumIdByRef, report)
                        resolveCovers(record, albumIdByRef, pendingCovers)
                        read++
                        if (read % PROGRESS_EVERY == 0) onProgress(read)
                    }

                    is SavedSearchRecord -> report = importSavedSearch(record, tagIdByRef, report)
                }
            }
        }

        if (!sawHeader) return@withContext report.copy(wrongFormat = true)
        onProgress(read)
        report
    }

    /**
     * Points an album at its cover once the item carrying that ref has been matched.
     *
     * A cover whose photo is not on this device simply stays unset, and the shelf falls back
     * to the album's first member — which is why `cover_media_id` is not a foreign key.
     */
    private suspend fun resolveCovers(
        record: ItemRecord,
        albumIdByRef: Map<Long, Long>,
        pending: MutableMap<Long, Long>,
    ) {
        if (pending.isEmpty() || record.ref == 0L) return
        val wanting = pending.filterValues { it == record.ref }.keys
        if (wanting.isEmpty()) return

        val local = matchesFor(record).firstOrNull()?.id
        wanting.forEach { albumRef ->
            pending.remove(albumRef)
            val albumId = albumIdByRef[albumRef] ?: return@forEach
            if (local != null) albums.setCover(albumId, local)
        }
    }

    /** The local rows this exported item corresponds to. See [applyItem] for the two tiers. */
    private suspend fun matchesFor(record: ItemRecord) =
        record.hash?.let { db.mediaDao().byContentHashAll(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: db.mediaDao().bySizeAndName(record.size, record.displayName)

    private suspend fun applyItem(
        record: ItemRecord,
        tagIdByRef: Map<Long, Long>,
        albumIdByRef: Map<Long, Long>,
        report: ImportReport,
    ): ImportReport {
        val byHash = record.hash?.let { db.mediaDao().byContentHashAll(it) }.orEmpty()
        // A hash resolves to a *set* of rows, and tagging all of them is the right
        // behaviour: identical content deserves identical tags.
        val matches = byHash.ifEmpty {
            db.mediaDao().bySizeAndName(record.size, record.displayName)
        }
        if (matches.isEmpty()) {
            return report.copy(itemsUnmatched = report.itemsUnmatched + 1)
        }

        val rows = record.tags.mapNotNull { assignment ->
            val tagId = tagIdByRef[assignment.tag] ?: return@mapNotNull null
            matches.map { media ->
                MediaTagCrossRef(
                    mediaId = media.id,
                    tagId = tagId,
                    // Imported tags are marked as such so it stays visible where they came
                    // from, but they never overwrite something applied by hand.
                    source = TagSource.Imported,
                    createdAt = assignment.createdAt,
                )
            }
        }.flatten()

        // Album membership keeps its exported position, so an album restores in the order
        // the user arranged it rather than in whatever order the file happened to be read.
        val albumRows = record.albums.mapNotNull { membership ->
            val albumId = albumIdByRef[membership.album] ?: return@mapNotNull null
            matches.map { media ->
                AlbumMediaCrossRef(
                    albumId = albumId,
                    mediaId = media.id,
                    position = membership.position,
                    addedAt = now(),
                )
            }
        }.flatten()

        var ocrRestored = 0
        var membershipsApplied = 0
        db.withTransaction {
            if (rows.isNotEmpty()) db.mediaTagDao().insertIgnoring(rows)
            if (albumRows.isNotEmpty()) {
                membershipsApplied = db.albumDao().addAll(albumRows).count { it != -1L }
            }
            if (!record.ocrText.isNullOrBlank()) {
                matches.filter { it.ocrText.isNullOrBlank() }.forEach {
                    db.mediaDao().setOcrText(it.id, record.ocrText)
                    ocrRestored++
                }
            }
            fts.rebuild(matches.map { it.id })
        }

        return report.copy(
            itemsMatchedByHash = report.itemsMatchedByHash + if (byHash.isNotEmpty()) 1 else 0,
            itemsMatchedByName = report.itemsMatchedByName + if (byHash.isEmpty()) 1 else 0,
            assignmentsApplied = report.assignmentsApplied + rows.size,
            albumMembershipsApplied = report.albumMembershipsApplied + membershipsApplied,
            ocrRestored = report.ocrRestored + ocrRestored,
        )
    }

    /**
     * Saved searches reference tag *ids*, which differ between installs, so they are
     * remapped through the file's tag refs. A search whose tags cannot all be resolved is
     * still imported — with the unresolvable ones dropped — because a partly-working smart
     * album beats a missing one.
     */
    private suspend fun importSavedSearch(
        record: SavedSearchRecord,
        tagIdByRef: Map<Long, Long>,
        report: ImportReport,
    ): ImportReport {
        if (db.savedSearchDao().all().any { it.name.equals(record.name, ignoreCase = true) }) {
            return report.copy(savedSearchesSkipped = report.savedSearchesSkipped + 1)
        }
        val query = SearchQuery.decode(record.queryJson).remapTags(tagIdByRef)
        db.savedSearchDao().insert(
            SavedSearchEntity(
                name = record.name,
                queryJson = query.encode(),
                createdAt = now(),
                updatedAt = now(),
                pinned = record.pinned,
            ),
        )
        return report.copy(savedSearchesImported = report.savedSearchesImported + 1)
    }

    private suspend fun resolvePath(path: List<String>): Long? {
        var parent = TagEntity.ROOT_PARENT_ID
        for (segment in path) {
            parent = db.tagDao().byNameUnder(parent, segment)?.id ?: return null
        }
        return parent
    }

    private companion object {
        const val PAGE = 500
        const val PROGRESS_EVERY = 200
        const val MAX_DEPTH = 32
    }
}

internal fun SearchQuery.remapTags(map: Map<Long, Long>): SearchQuery = copy(
    allTags = allTags.mapNotNull { map[it] },
    anyTags = anyTags.mapNotNull { map[it] },
    noneTags = noneTags.mapNotNull { map[it] },
    // bucketIds are MediaStore's, not ours, and mean nothing on a different device.
    bucketIds = emptyList(),
)
