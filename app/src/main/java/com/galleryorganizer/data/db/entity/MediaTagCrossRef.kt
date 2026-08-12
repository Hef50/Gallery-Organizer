package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Where a tag on an item came from. The [wire] value is what lands in the JSON backup. */
enum class TagSource(val wire: String) {
    /** The user applied it. Never overwritten by anything automatic. */
    Manual("manual"),

    /** ML Kit or another heuristic applied it, after the user promoted a suggestion. */
    Auto("auto"),

    /** Came in from a JSON restore or an XMP sidecar. */
    Imported("imported"),

    ;

    companion object {
        fun fromWire(value: String): TagSource =
            entries.firstOrNull { it.wire == value } ?: Manual
    }
}

/**
 * The tag ↔ media join. Deliberately narrow: two integers, a short string and a
 * timestamp, because at 150k items with a dozen tags each this is the biggest table in
 * the database.
 */
@Entity(
    tableName = "media_tag",
    primaryKeys = ["media_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // (media_id, tag_id) is already covered by the primary key; only the reverse
    // direction — "everything tagged X" — needs its own index.
    indices = [Index(value = ["tag_id"])],
)
data class MediaTagCrossRef(
    @ColumnInfo(name = "media_id")
    val mediaId: Long,

    @ColumnInfo(name = "tag_id")
    val tagId: Long,

    /**
     * Stored as the [TagSource.wire] string rather than an enum ordinal: these values are
     * written verbatim into the JSON backup, and an ordinal would silently change meaning
     * if the enum were ever reordered.
     */
    @ColumnInfo(name = "source")
    val source: TagSource = TagSource.Manual,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)
