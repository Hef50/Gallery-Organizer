package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per item in the device's media library.
 *
 * This table is a *cache and an index* over MediaStore, never a copy of the media. The
 * app owns [contentHash] and the tag relations; MediaStore owns the files.
 *
 * Identity: [contentHash] is the stable identity that survives reindexing, file moves,
 * reinstalls and MediaStore id churn. [mediaStoreId] is only a mutable address, refreshed
 * on every index pass. [id] is a local synthetic key so that `media_tag` rows stay narrow.
 */
@Entity(
    tableName = "media",
    indices = [
        // Indexed but deliberately NOT unique — see the note on [contentHash].
        Index(value = ["content_hash"]),
        Index(value = ["mediastore_id"]),
        Index(value = ["date_taken"]),
        Index(value = ["date_modified"]),
        /**
         * The grid's index, added in schema v4 — and the single most important one in the
         * database.
         *
         * The grid is always `WHERE is_missing = 0 ... ORDER BY date_taken DESC, id DESC`.
         * With separate single-column indices SQLite chose `index_media_is_missing`, which
         * has two distinct values and therefore excludes nothing, and then sorted the whole
         * result in a temp B-tree — 150,000 rows sorted in memory for every grid load.
         * This composite lets it seek `is_missing = 0` and walk the range already in date
         * order, so `LIMIT` stops early and no sort happens at all. `QueryPlanTest` is what
         * caught that and is what stops it coming back.
         *
         * The single-column `is_missing` and `is_video` indices were *removed* in the same
         * migration: this composite serves every `is_missing` lookup via its leftmost
         * column, and a two-value index is otherwise only useful for misleading the planner.
         */
        Index(value = ["is_missing", "date_taken", "id"]),
        /** Same shape for a folder-filtered grid, where date order still has to hold. */
        Index(value = ["bucket_id", "date_taken"]),
        // Added in schema v2 for restore's fallback match: an item tagged before it was
        // ever hashed can only be found again by (size, name), and that lookup runs once
        // per backed-up item. Without the index it is a full table scan each time.
        Index(value = ["size", "display_name"]),
        Index(value = ["auto_scan_state"]),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * `size + ':' + sha256(first 64 KiB) + ':' + dateTaken`, hex. Null until the item is
     * hashed — hashing is lazy (first tag write, or the idle backfill worker), because
     * hashing 150k files up front is minutes of I/O for no benefit.
     *
     * **Not unique**, despite the brief's schema sketch saying `content_hash UNIQUE`. A
     * unique index would mean the second copy of a genuinely duplicated file could not be
     * indexed at all — the insert would simply fail — which both hides real files from the
     * grid and makes P10's duplicate finder impossible, since it looks for exactly this:
     * two present rows sharing a hash. The uniqueness the brief was reaching for is
     * "one row per file", and that is already guaranteed by matching on
     * [mediaStoreId] during indexing. Restore therefore resolves a hash to a *set* of
     * rows and tags all of them, which is the behaviour you want anyway: identical
     * content deserves identical tags. See DECISIONS.md.
     */
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    /** Mutable cache of `MediaStore._ID`. Reconciled every index pass; never durable. */
    @ColumnInfo(name = "mediastore_id")
    val mediaStoreId: Long,

    /** Content URI, stored so Coil and the file readers do not have to rebuild it. */
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** e.g. `DCIM/Camera/`. Empty string rather than null when MediaStore omits it. */
    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    @ColumnInfo(name = "bucket_id")
    val bucketId: Long,

    @ColumnInfo(name = "bucket_name")
    val bucketName: String,

    @ColumnInfo(name = "mime")
    val mime: String,

    /** Denormalised from [mime] so the grid can filter without a LIKE. */
    @ColumnInfo(name = "is_video")
    val isVideo: Boolean,

    @ColumnInfo(name = "size")
    val size: Long,

    /**
     * Milliseconds. MediaStore's `DATE_TAKEN` when present, otherwise `DATE_MODIFIED`
     * promoted to milliseconds — a lot of non-camera files have no `DATE_TAKEN` at all
     * and would otherwise pile up at the epoch.
     */
    @ColumnInfo(name = "date_taken")
    val dateTaken: Long,

    /** Seconds, as MediaStore reports it. This is what drives the incremental watermark. */
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,

    /** Seconds. When the file arrived on the device according to MediaStore. */
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,

    /** Milliseconds, ours. When *this app* first saw the item — drives "recently added". */
    @ColumnInfo(name = "date_first_indexed")
    val dateFirstIndexed: Long,

    /** Milliseconds; 0 for images. */
    @ColumnInfo(name = "duration")
    val duration: Long = 0,

    @ColumnInfo(name = "width")
    val width: Int = 0,

    @ColumnInfo(name = "height")
    val height: Int = 0,

    /** Degrees, as MediaStore reports. */
    @ColumnInfo(name = "orientation")
    val orientation: Int = 0,

    /**
     * Set when the indexer stops seeing the file. The row and its tags are *kept* — files
     * come back, and throwing away tags on a transient absence is exactly the data loss
     * this app exists to prevent. See DECISIONS.md.
     */
    @ColumnInfo(name = "is_missing")
    val isMissing: Boolean = false,

    /** Filled by the P9 OCR worker. Indexed by `media_fts` from v1 onward. */
    @ColumnInfo(name = "ocr_text")
    val ocrText: String? = null,

    /**
     * How far on-device analysis has got with this item, added in schema v3.
     * 0 = not looked at, 1 = analysed, 2 = analysis failed (a corrupt or unreadable file).
     *
     * Stored on the row rather than derived from whether suggestions exist, because "we
     * looked and found nothing" and "we have not looked yet" are different states and
     * conflating them would make the worker re-analyse every featureless photo forever.
     */
    @ColumnInfo(name = "auto_scan_state")
    val autoScanState: Int = AUTO_SCAN_PENDING,
) {
    companion object {
        const val AUTO_SCAN_PENDING = 0
        const val AUTO_SCAN_DONE = 1
        const val AUTO_SCAN_FAILED = 2
    }
}
