package com.galleryorganizer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** What the user has decided about a suggestion. */
enum class SuggestionStatus(val wire: String) {
    /** Waiting in the review queue. */
    Pending("pending"),

    /** Promoted to a real tag with `source = auto`. */
    Accepted("accepted"),

    /** The user said no. Kept so the same label is never suggested for this item again. */
    Rejected("rejected"),

    ;

    companion object {
        fun fromWire(value: String): SuggestionStatus =
            entries.firstOrNull { it.wire == value } ?: Pending
    }
}

/**
 * A label ML Kit proposed for an item, added in schema v3.
 *
 * Suggestions are a separate table rather than `media_tag` rows so that nothing automatic
 * ever appears as a tag the user did not ask for. A rejected suggestion is *kept* — that
 * is the only way the next scan can avoid offering the same wrong label again.
 */
@Entity(
    tableName = "label_suggestion",
    primaryKeys = ["media_id", "label"],
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["status", "label"]), Index(value = ["label"])],
)
data class LabelSuggestionEntity(
    @ColumnInfo(name = "media_id")
    val mediaId: Long,

    @ColumnInfo(name = "label", collate = ColumnInfo.NOCASE)
    val label: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "status")
    val status: SuggestionStatus = SuggestionStatus.Pending,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)
