package com.galleryorganizer.data.repo

import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.entity.MediaFtsEntity
import com.galleryorganizer.data.db.entity.TagEntity

/**
 * Keeps `media_fts` in step with the three things it indexes: filenames/paths, the tag
 * names on an item, and OCR text.
 *
 * Every write that changes one of those inputs must call [rebuild] *inside the same
 * transaction*, or the text index quietly drifts from the data. Centralising it here
 * means there is exactly one place to get that right.
 */
class FtsMaintenance(private val db: AppDatabase) {

    /**
     * Rebuilds the FTS rows for [mediaIds]. Must be called inside a transaction.
     *
     * Tag text includes every ancestor name, so an item tagged `Travel/Japan/Kyoto` is
     * found by typing "travel" as well as "kyoto" — matching how the structured tag
     * filter expands a parent to its subtree (OPEN_QUESTIONS.md #4).
     */
    suspend fun rebuild(mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        // `IN (...)` binds one variable per id and SQLite caps them, so a bulk tag of a
        // few thousand items has to be split. The chunks stay inside the caller's
        // transaction, so this is still all-or-nothing.
        mediaIds.chunked(CHUNK).forEach { rebuildChunk(it) }
    }

    private suspend fun rebuildChunk(mediaIds: List<Long>) {
        val rows = db.mediaDao().byIds(mediaIds)
        if (rows.isEmpty()) {
            db.mediaFtsDao().deleteRows(mediaIds)
            return
        }

        val paths = TagPathIndex(db.tagDao().allTags())
        // tagNamesFor gives the names actually written on each item; the ancestors come
        // from the path index.
        val tagsByMedia = db.mediaTagDao().tagNamesFor(mediaIds).groupBy { it.mediaId }
        val expanded = mediaIds.associateWith { id ->
            tagsByMedia[id].orEmpty()
                .flatMap { paths.withAncestors(it.name) }
                .distinct()
                .joinToString(" ")
        }

        db.mediaFtsDao().deleteRows(mediaIds)
        db.mediaFtsDao().insertRows(
            rows.map { media ->
                MediaFtsEntity(
                    rowId = media.id,
                    displayName = media.displayName,
                    relativePath = media.relativePath,
                    tags = expanded[media.id].orEmpty(),
                    ocrText = media.ocrText.orEmpty(),
                )
            },
        )
    }

    private companion object {
        const val CHUNK = 500
    }
}

/**
 * Resolves a tag name to itself plus every ancestor name. Tags number in the hundreds at
 * most, so building this per call is cheaper than a recursive query per item.
 */
internal class TagPathIndex(tags: List<TagEntity>) {
    private val byId = tags.associateBy { it.id }
    private val byName = tags.groupBy { it.name.lowercase() }

    fun withAncestors(name: String): List<String> {
        val matches = byName[name.lowercase()].orEmpty()
        if (matches.isEmpty()) return listOf(name)
        return matches.flatMap { chain(it) }.distinct()
    }

    private fun chain(tag: TagEntity): List<String> {
        val names = mutableListOf(tag.name)
        var parent = byId[tag.parentId]
        var guard = 0
        while (parent != null && guard++ < MAX_DEPTH) {
            names += parent.name
            parent = byId[parent.parentId]
        }
        return names
    }

    private companion object {
        // Cheap insurance against a cycle introduced by a bad import.
        const val MAX_DEPTH = 32
    }
}
