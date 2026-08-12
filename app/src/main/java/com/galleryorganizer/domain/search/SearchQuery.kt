package com.galleryorganizer.domain.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class MediaTypeFilter {
    @SerialName("any")
    Any,

    @SerialName("photos")
    Photos,

    @SerialName("videos")
    Videos,
}

@Serializable
enum class SortOrder {
    @SerialName("newest")
    NewestFirst,

    @SerialName("oldest")
    OldestFirst,

    @SerialName("recently_added")
    RecentlyAdded,

    @SerialName("largest")
    Largest,
}

/**
 * A saved search, and the thing the grid is filtered by.
 *
 * Serialised to JSON in `saved_search.query_json` rather than to columns, so the query
 * language can gain a field without a schema migration and without invalidating searches
 * the user already saved. Every field has a default, which is what makes an older saved
 * search still deserialise after the language grows.
 */
@Serializable
data class SearchQuery(
    /** Free text, matched against filename, path, tag names and OCR text via FTS. */
    val text: String = "",

    /** Every one of these must be present — AND. */
    val allTags: List<Long> = emptyList(),

    /** At least one of these must be present — OR. */
    val anyTags: List<Long> = emptyList(),

    /** None of these may be present — NOT. */
    val noneTags: List<Long> = emptyList(),

    /**
     * When true (the default), a tag filter also matches items tagged with any descendant,
     * so searching `Travel` finds `Travel/Japan/Kyoto`. See OPEN_QUESTIONS.md #4.
     */
    val expandSubtrees: Boolean = true,

    val mediaType: MediaTypeFilter = MediaTypeFilter.Any,

    val bucketIds: List<Long> = emptyList(),

    /** Inclusive, milliseconds, compared against `date_taken`. */
    val takenFrom: Long? = null,

    /** Inclusive, milliseconds. */
    val takenTo: Long? = null,

    /** Only items with no tags at all — the "what still needs organising" view. */
    val untaggedOnly: Boolean = false,

    val includeMissing: Boolean = false,

    val sort: SortOrder = SortOrder.NewestFirst,
) {
    /** True when this would match the whole library, so the UI can skip the filter chrome. */
    val isEmpty: Boolean
        get() = text.isBlank() && allTags.isEmpty() && anyTags.isEmpty() && noneTags.isEmpty() &&
            mediaType == MediaTypeFilter.Any && bucketIds.isEmpty() && takenFrom == null &&
            takenTo == null && !untaggedOnly && !includeMissing

    val activeFilterCount: Int
        get() = listOf(
            text.isNotBlank(),
            allTags.isNotEmpty(),
            anyTags.isNotEmpty(),
            noneTags.isNotEmpty(),
            mediaType != MediaTypeFilter.Any,
            bucketIds.isNotEmpty(),
            takenFrom != null || takenTo != null,
            untaggedOnly,
        ).count { it }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Never throws. A saved search written by a future build, or one corrupted on
         * disk, degrades to "everything" rather than crashing the screen that lists them.
         */
        fun decode(json: String): SearchQuery = runCatching {
            JSON.decodeFromString(serializer(), json)
        }.getOrElse { SearchQuery() }
    }
}
