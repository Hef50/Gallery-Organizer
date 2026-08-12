package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What sort of thing a tag names, added in schema v5.
 *
 * Tags were previously all one undifferentiated kind, which works until you have two
 * hundred of them and cannot tell "Anna" from "Antwerp" from "Anniversary" in a flat list.
 * A kind gives each tag an icon, a colour and a section, so the picker can group them the
 * way people actually think — who, where, when, what — instead of alphabetically.
 *
 * The wire values are written into the JSON backup, so they are explicit strings rather
 * than enum ordinals.
 */
enum class TagKind(val wire: String, val label: String) {
    Person("person", "People"),
    Place("place", "Places"),
    Event("event", "Events"),
    Thing("thing", "Things"),

    /** The default, and the escape hatch for anything that is none of the above. */
    Note("note", "Other"),
    ;

    companion object {
        fun fromWire(value: String): TagKind = entries.firstOrNull { it.wire == value } ?: Note
    }
}

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
        Index(value = ["kind"]),
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

    /** See [TagKind]. Children default to their parent's kind when created in the UI. */
    @ColumnInfo(name = "kind")
    val kind: TagKind = TagKind.Note,
) {
    companion object {
        const val ROOT_PARENT_ID: Long = 0
    }
}
