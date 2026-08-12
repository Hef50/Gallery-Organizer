package com.galleryorganizer.ui.grid

import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SortOrder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuickFilterTest {

    @Test
    fun `a quick filter keeps the search text but replaces the structured filter`() {
        // Tapping "Videos" mid-search should narrow that search, not throw it away.
        val current = SearchQuery(text = "kyoto", allTags = listOf(1, 2), untaggedOnly = true)

        val filtered = QuickFilter.Videos.apply(current)

        assertThat(filtered.text).isEqualTo("kyoto")
        assertThat(filtered.mediaType).isEqualTo(MediaTypeFilter.Videos)
        assertThat(filtered.allTags).isEmpty()
        assertThat(filtered.untaggedOnly).isFalse()
    }

    @Test
    fun `recently added sorts by when this app first saw the item`() {
        // Not MediaStore's DATE_ADDED and not DATE_TAKEN: a photo restored from a backup
        // has a brand-new DATE_ADDED and a years-old DATE_TAKEN, and neither is "new to
        // you". See OPEN_QUESTIONS.md #6.
        assertThat(QuickFilter.RecentlyAdded.apply(SearchQuery()).sort)
            .isEqualTo(SortOrder.RecentlyAdded)
    }

    @Test
    fun `All clears everything except the search text`() {
        val current = SearchQuery(text = "kyoto", mediaType = MediaTypeFilter.Videos)
        assertThat(QuickFilter.All.apply(current)).isEqualTo(SearchQuery(text = "kyoto"))
    }

    @Test
    fun `each chip recognises its own state and only its own`() {
        QuickFilter.entries.forEach { filter ->
            val applied = filter.apply(SearchQuery())
            assertThat(filter.matches(applied)).isTrue()
            QuickFilter.entries.filter { it != filter }.forEach { other ->
                assertThat(other.matches(applied)).isFalse()
            }
        }
    }

    @Test
    fun `hidden folders do not make a chip look unselected`() {
        // Hiding a folder is a view preference applied downstream, so it must not leak
        // into the query the chips compare against.
        val query = QuickFilter.Untagged.apply(SearchQuery())
        assertThat(QuickFilter.Untagged.matches(query)).isTrue()
    }
}
