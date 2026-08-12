package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A hand-made collection, added in schema v5.
 *
 * Deliberately *not* the same thing as a tag, even though both group photos. A tag is a
 * fact about a photo ("this is Kyoto") and belongs to every photo that fits it. An album
 * is a curated sequence someone assembled on purpose ("the twelve shots from the trip
 * worth showing my mother") — it has an order, a cover, and membership that is nobody's
 * business but the user's. Folding one into the other would lose the ordering and make
 * "everything tagged Kyoto" and "my Kyoto album" the same list, which they are not.
 *
 * A smart album — a saved search — is the third case, and already exists separately.
 */
@Entity(
    tableName = "album",
    indices = [Index(value = ["name"], unique = true)],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    /**
     * Nullable, and *not* a foreign key: an album should keep pointing at its chosen cover
     * even if that photo temporarily goes missing, and a cascade would silently clear it.
     * The UI falls back to the first member when the cover cannot be resolved.
     */
    @ColumnInfo(name = "cover_media_id")
    val coverMediaId: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

/**
 * Album membership, with an explicit [position] because the order *is* the content of an
 * album. Positions are sparse (gaps of [POSITION_STEP]) so that dragging one photo between
 * two others rewrites one row instead of renumbering the whole album.
 */
@Entity(
    tableName = "album_media",
    primaryKeys = ["album_id", "media_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["media_id"]), Index(value = ["album_id", "position"])],
)
data class AlbumMediaCrossRef(
    @ColumnInfo(name = "album_id")
    val albumId: Long,

    @ColumnInfo(name = "media_id")
    val mediaId: Long,

    @ColumnInfo(name = "position")
    val position: Long,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = 0,
) {
    companion object {
        const val POSITION_STEP = 1_000L
    }
}
