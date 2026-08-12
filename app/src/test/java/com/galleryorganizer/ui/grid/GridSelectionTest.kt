package com.galleryorganizer.ui.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GridSelectionTest {

    @Test
    fun `tapping toggles`() {
        val state = SelectionState().toggle(1).toggle(2)
        assertThat(state.selected).containsExactly(1L, 2L)
        assertThat(state.toggle(1).selected).containsExactly(2L)
    }

    @Test
    fun `a drag selects the range it covers`() {
        val state = SelectionState()
            .beginDrag(anchorIndex = 0, anchorId = 1)
            .extendDrag(listOf(1L, 2L, 3L))

        assertThat(state.selected).containsExactly(1L, 2L, 3L)
        assertThat(state.dragging).isTrue()
    }

    @Test
    fun `dragging back over items deselects them again`() {
        // Recomputed from the anchor each move rather than accumulated, so an overshoot
        // is correctable without lifting a finger.
        val state = SelectionState()
            .beginDrag(anchorIndex = 0, anchorId = 1)
            .extendDrag(listOf(1L, 2L, 3L, 4L))
            .extendDrag(listOf(1L, 2L))

        assertThat(state.selected).containsExactly(1L, 2L)
    }

    @Test
    fun `a drag adds to what was already selected instead of replacing it`() {
        val state = SelectionState()
            .toggle(99)
            .beginDrag(anchorIndex = 3, anchorId = 5)
            .extendDrag(listOf(5L, 6L))

        assertThat(state.selected).containsExactly(99L, 5L, 6L)
    }

    @Test
    fun `shrinking a drag never eats the pre-existing selection`() {
        val state = SelectionState()
            .toggle(99)
            .beginDrag(anchorIndex = 0, anchorId = 1)
            .extendDrag(listOf(1L, 2L, 3L))
            .extendDrag(listOf(1L))

        assertThat(state.selected).containsExactly(99L, 1L)
    }

    @Test
    fun `ending a drag commits it so the next drag builds on top`() {
        val state = SelectionState()
            .beginDrag(anchorIndex = 0, anchorId = 1)
            .extendDrag(listOf(1L, 2L))
            .endDrag()

        assertThat(state.dragging).isFalse()
        assertThat(state.anchorIndex).isNull()

        val second = state.beginDrag(anchorIndex = 9, anchorId = 10).extendDrag(listOf(10L, 11L))
        assertThat(second.selected).containsExactly(1L, 2L, 10L, 11L)
    }

    @Test
    fun `clearing resets everything including a drag in flight`() {
        val state = SelectionState()
            .beginDrag(anchorIndex = 0, anchorId = 1)
            .extendDrag(listOf(1L, 2L))
            .clear()

        assertThat(state.active).isFalse()
        assertThat(state.dragging).isFalse()
        assertThat(state.anchorIndex).isNull()
    }

    @Test
    fun `auto-scroll is off in the middle and ramps towards the edges`() {
        val height = 1000f
        val edge = 100f

        assertThat(autoScrollSpeed(y = 500f, height = height, edge = edge)).isEqualTo(0f)
        assertThat(autoScrollSpeed(y = 50f, height = height, edge = edge)).isLessThan(0f)
        assertThat(autoScrollSpeed(y = 950f, height = height, edge = edge)).isGreaterThan(0f)

        // Deeper into the zone is faster, so a small overshoot creeps and a hard push flies.
        val shallow = autoScrollSpeed(y = 990f, height = height, edge = edge)
        val deep = autoScrollSpeed(y = 999f, height = height, edge = edge)
        assertThat(deep).isGreaterThan(shallow)
    }

    @Test
    fun `video durations are formatted for a badge, not a stopwatch`() {
        assertThat(formatDuration(0)).isEqualTo("0:00")
        assertThat(formatDuration(9_000)).isEqualTo("0:09")
        assertThat(formatDuration(95_000)).isEqualTo("1:35")
        assertThat(formatDuration(3_725_000)).isEqualTo("1:02:05")
    }
}
