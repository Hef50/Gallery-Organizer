package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A node in the tag hierarchy — a plain adjacency list. Depth is expected to be two or
 * three (`Travel/Japan/Kyoto`), so recursive CTEs for subtree queries stay cheap and the
 * write path stays trivial.
 *
 * Root tags use [ROOT_PARENT_ID] (0) rather than `NULL` for [parentId]. A `NULL` parent
 * would defeat the `(parent_id, name)` unique index, because SQLite considers every NULL
 * distinct and two root tags called "Travel" would both be allowed. The cost is that
 * there is no foreign key on `parent_id`; subtree deletion is done explicitly in
 * [com.galleryorganizer.data.db.dao.TagDao]. See DECISIONS.md.
 */
@Entity(
    tableName = "tag",
    indices = [
        Index(value = ["parent_id", "name"], unique = true),
        Index(value = ["parent_id"]),
    ],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Case-insensitive for uniqueness, but stored with the user's own capitalisation. */
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "parent_id")
    val parentId: Long = ROOT_PARENT_ID,

    /** ARGB, or null to let the UI pick from the theme. */
    @ColumnInfo(name = "color")
    val color: Int? = null,

    /** Bumped on every tag application so the bulk sheet can offer "recently used". */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = 0,

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,
) {
    companion object {
        const val ROOT_PARENT_ID: Long = 0
    }
}
