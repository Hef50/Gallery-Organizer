package com.galleryorganizer.ui.grid

import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SortOrder

/**
 * The one-tap views above the grid.
 *
 * Each one *replaces* the structured filter rather than adding to it, but keeps whatever is
 * in the search box — tapping "Videos" while a search is running should narrow that search,
 * not throw it away.
 */
enum class QuickFilter(val label: String) {
    All("All"),

    /**
     * Ordered by when *this app* first saw the item, not MediaStore's `DATE_ADDED` and not
     * `DATE_TAKEN`. A photo restored from a backup has a brand-new `DATE_ADDED` and a
     * years-old `DATE_TAKEN`; neither is "new to you". See OPEN_QUESTIONS.md #6.
     */
    RecentlyAdded("Recently added"),

    /** What still needs organising. */
    Untagged("Untagged"),

    Videos("Videos"),
    ;

    fun apply(current: SearchQuery): SearchQuery {
        val base = SearchQuery(text = current.text)
        return when (this) {
            All -> base
            RecentlyAdded -> base.copy(sort = SortOrder.RecentlyAdded)
            Untagged -> base.copy(untaggedOnly = true)
            Videos -> base.copy(mediaType = MediaTypeFilter.Videos)
        }
    }

    /** True when [query] is exactly what this chip produces, so it can render as selected. */
    fun matches(query: SearchQuery): Boolean = apply(query) == query
}
