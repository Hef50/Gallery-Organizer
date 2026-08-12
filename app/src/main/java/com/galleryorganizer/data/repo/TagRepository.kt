package com.galleryorganizer.data.repo

import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.data.db.entity.TagSource
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.buildTagTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether every, some or none of the selected items carry a tag. */
enum class TagCheckState { None, Some, All }

/**
 * Everything that writes tags.
 *
 * The rule that governs this whole class: **one user gesture is one database
 * transaction.** Tagging 500 selected photos writes 500 cross-ref rows, bumps the tag's
 * usage counters and rebuilds 500 FTS rows atomically, so it either all happened or none
 * of it did, and the grid never shows a half-applied tag.
 */
class TagRepository(
    private val db: AppDatabase,
    private val fts: FtsMaintenance = FtsMaintenance(db),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val tagDao get() = db.tagDao()
    private val mediaTagDao get() = db.mediaTagDao()

    fun observeTree(): Flow<List<TagNode>> = tagDao.observeAllWithCounts().map(::buildTagTree)

    fun observeRecentlyUsed(limit: Int = 12): Flow<List<TagEntity>> =
        tagDao.observeRecentlyUsed(limit)

    suspend fun allTags(): List<TagEntity> = tagDao.allTags()

    fun observeTagsFor(mediaId: Long): Flow<List<TagEntity>> = mediaTagDao.observeTagsFor(mediaId)

    // --- Tag hierarchy ----------------------------------------------------------------

    /**
     * Returns the existing tag with this name under this parent, or creates it.
     *
     * Idempotent on purpose: the bulk sheet's "create and apply" is one gesture and must
     * not fail because the user typed a name that already exists.
     */
    suspend fun ensureTag(
        name: String,
        parentId: Long = TagEntity.ROOT_PARENT_ID,
        kind: TagKind? = null,
    ): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A tag needs a name" }
        return db.withTransaction {
            tagDao.byNameUnder(parentId, clean)?.id ?: run {
                // A new child defaults to its parent's kind: someone adding "Kyoto" under
                // "Japan" means another place, and asking them to say so again is friction
                // for no information.
                val resolved = kind
                    ?: parentId.takeIf { it != TagEntity.ROOT_PARENT_ID }
                        ?.let { tagDao.byId(it)?.kind }
                    ?: TagKind.Note
                tagDao.insert(TagEntity(name = clean, parentId = parentId, kind = resolved))
            }
        }
    }

    /** Re-kinds a tag and everything under it. See [com.galleryorganizer.data.db.dao.TagDao.setKind]. */
    suspend fun setKind(tagId: Long, kind: TagKind) = db.withTransaction {
        tagDao.setKind(tagDao.subtreeIds(tagId), kind)
    }

    /** `Travel/Japan/Kyoto` — creates any missing level and returns the leaf's id. */
    suspend fun ensurePath(path: List<String>): Long {
        require(path.isNotEmpty()) { "A tag path needs at least one segment" }
        var parent = TagEntity.ROOT_PARENT_ID
        for (segment in path) {
            parent = ensureTag(segment, parent)
        }
        return parent
    }

    suspend fun rename(tagId: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A tag needs a name" }
        db.withTransaction {
            val tag = tagDao.byId(tagId) ?: return@withTransaction
            tagDao.update(tag.copy(name = clean))
            rebuildFtsForTagSubtree(tagId)
        }
    }

    suspend fun setColor(tagId: Long, color: Int?) {
        val tag = tagDao.byId(tagId) ?: return
        tagDao.update(tag.copy(color = color))
    }

    /**
     * Re-parents a tag.
     *
     * Refuses to move a tag into its own subtree — that would detach the whole branch from
     * the roots and make it unreachable in the UI while its rows quietly remain.
     */
    suspend fun move(tagId: Long, newParentId: Long): Boolean = db.withTransaction {
        if (tagId == newParentId) return@withTransaction false
        if (newParentId != TagEntity.ROOT_PARENT_ID && newParentId in tagDao.subtreeIds(tagId)) {
            return@withTransaction false
        }
        val tag = tagDao.byId(tagId) ?: return@withTransaction false
        if (tagDao.byNameUnder(newParentId, tag.name)?.takeIf { it.id != tagId } != null) {
            // A sibling already owns that name; the unique index would reject it anyway.
            return@withTransaction false
        }
        tagDao.update(tag.copy(parentId = newParentId))
        rebuildFtsForTagSubtree(tagId)
        true
    }

    /** Deletes a tag and everything under it, along with every assignment. Media untouched. */
    suspend fun deleteTag(tagId: Long) = db.withTransaction {
        val affected = affectedMediaIds(tagDao.subtreeIds(tagId))
        tagDao.deleteSubtree(tagId)
        fts.rebuild(affected)
    }

    // --- Applying tags ----------------------------------------------------------------

    /**
     * Applies [tagIds] to [mediaIds] in one transaction.
     *
     * Insert conflicts are IGNOREd rather than REPLACEd so that re-applying a tag an item
     * already carries manually cannot downgrade it to `auto`, and so re-tagging is a
     * harmless no-op rather than a `created_at` rewrite.
     *
     * @return the number of assignments actually created.
     */
    suspend fun applyTags(
        mediaIds: Collection<Long>,
        tagIds: Collection<Long>,
        source: TagSource = TagSource.Manual,
    ): Int {
        if (mediaIds.isEmpty() || tagIds.isEmpty()) return 0
        val timestamp = now()
        return db.withTransaction {
            val rows = mediaIds.flatMap { mediaId ->
                tagIds.map { tagId -> MediaTagCrossRef(mediaId, tagId, source, timestamp) }
            }
            val inserted = rows.chunked(WRITE_CHUNK)
                .sumOf { chunk -> mediaTagDao.insertIgnoring(chunk).count { it != -1L } }

            if (source == TagSource.Manual) {
                tagDao.touchUsage(tagIds.toList(), timestamp, delta = mediaIds.size)
            }
            fts.rebuild(mediaIds.toList())
            inserted
        }
    }

    suspend fun removeTags(mediaIds: Collection<Long>, tagIds: Collection<Long>) {
        if (mediaIds.isEmpty() || tagIds.isEmpty()) return
        db.withTransaction {
            val ids = mediaIds.toList()
            tagIds.forEach { tagId ->
                ids.chunked(WRITE_CHUNK).forEach { mediaTagDao.removeTagFrom(tagId, it) }
            }
            fts.rebuild(ids)
        }
    }

    /** Toggling a tri-state checkbox in the bulk sheet: partial coverage applies to all. */
    suspend fun setTag(mediaIds: Collection<Long>, tagId: Long, checked: Boolean): Int =
        if (checked) {
            applyTags(mediaIds, listOf(tagId))
        } else {
            removeTags(mediaIds, listOf(tagId))
            0
        }

    /**
     * How many of [mediaIds] carry each tag, collapsed into the tri-state the bulk sheet
     * renders.
     */
    suspend fun coverage(mediaIds: Collection<Long>): Map<Long, TagCheckState> {
        if (mediaIds.isEmpty()) return emptyMap()
        val total = mediaIds.size
        val counts = HashMap<Long, Int>()
        mediaIds.chunked(WRITE_CHUNK).forEach { chunk ->
            mediaTagDao.coverageFor(chunk).forEach { row ->
                counts[row.tagId] = (counts[row.tagId] ?: 0) + row.itemCount
            }
        }
        return counts.mapValues { (_, count) ->
            when {
                count >= total -> TagCheckState.All
                count > 0 -> TagCheckState.Some
                else -> TagCheckState.None
            }
        }
    }

    suspend fun promoteAutoTagsToManual(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        db.withTransaction {
            mediaIds.toList().chunked(WRITE_CHUNK).forEach {
                mediaTagDao.changeSource(it, from = TagSource.Auto, to = TagSource.Manual)
            }
        }
    }

    // --- internals --------------------------------------------------------------------

    /** Items affected by a change to any of [tagIds], so their FTS text can be rebuilt. */
    private suspend fun affectedMediaIds(tagIds: List<Long>): List<Long> =
        tagIds.flatMap { tagId ->
            mediaTagDao.mediaIdsFor(tagId)
        }.distinct()

    private suspend fun rebuildFtsForTagSubtree(tagId: Long) {
        fts.rebuild(affectedMediaIds(tagDao.subtreeIds(tagId)))
    }

    private companion object {
        /** Keeps `IN (...)` clauses under SQLite's bound-variable ceiling. */
        const val WRITE_CHUNK = 500
    }
}
