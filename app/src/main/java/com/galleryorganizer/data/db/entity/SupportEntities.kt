package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/** A saved smart album: a serialised [com.galleryorganizer.domain.search.SearchQuery]. */
@Entity(tableName = "saved_search")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    /** JSON, so the query language can gain fields without a schema migration. */
    @ColumnInfo(name = "query_json")
    val queryJson: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,

    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

/**
 * Free-form key/value scratch space for the indexer: the `DATE_MODIFIED` watermark, the
 * resume cursor, the last completed run. A table rather than DataStore so it participates
 * in the same transaction as the rows it describes — otherwise a crash between "wrote
 * 500 rows" and "advanced the watermark" would skip items.
 */
@Entity(tableName = "index_state")
data class IndexStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,
)

/**
 * Full-text index over everything textual about an item.
 *
 * This is a *standalone* FTS4 table, not a Room `contentEntity` mirror, because the
 * searchable text spans three sources — `media.display_name` / `relative_path`, the
 * joined `tag.name` set, and `media.ocr_text` — and an external-content FTS table can
 * only shadow a single table. `rowid` is `media.id`, and every write that changes an
 * input rebuilds this row inside the same transaction. See DECISIONS.md.
 */
@Fts4
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    /** Space-joined tag names, including ancestors, so `Kyoto` also matches on `Travel`. */
    @ColumnInfo(name = "tags")
    val tags: String,

    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
)
