package com.galleryorganizer.ui.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galleryorganizer.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How many photos fit across the screen.
 *
 * A fixed set of steps rather than free scaling. Continuous column counts sound nicer but
 * are worse to use: you cannot return to the density you liked, thumbnails land on
 * fractional pixels, and every intermediate value costs a full re-layout of the grid. Five
 * steps cover everything from "read the expression on a face" to "find the one yellow
 * photo in a year".
 */
enum class GridDensity(val columns: Int, val spacing: Dp, val corner: Dp) {
    /** One at a time — the closest the grid gets to the viewer. */
    Single(1, 0.dp, 0.dp),

    Comfortable(2, 3.dp, 14.dp),

    /** The default. Big enough to recognise a photo, dense enough to scan. */
    Default(4, 2.dp, 8.dp),

    Dense(6, 1.5.dp, 5.dp),

    /** Scanning a whole year. Corners go square: at this size rounding is just noise. */
    Overview(10, 1.dp, 2.dp),
    ;

    val zoomedIn: GridDensity? get() = entries.getOrNull(ordinal - 1)
    val zoomedOut: GridDensity? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val Fallback = Default
    }
}

/**
 * Drives pinch-to-zoom over the grid.
 *
 * The grid cannot re-flow continuously under the finger — re-laying out thousands of cells
 * per frame would drop every one of them. Instead the whole grid is *scaled* while the
 * pinch is in progress, which is free (one layer transform), and the column count is
 * committed once when the gesture crosses a threshold. The scale then springs back to 1
 * against the new layout, so the eye reads one continuous zoom even though the layout
 * changed exactly once.
 */
@Stable
class GridZoomState(
    initial: GridDensity,
    private val scope: CoroutineScope,
    private val onDensityChanged: (GridDensity) -> Unit,
) {
    var density: GridDensity by mutableStateOf(initial)
        private set

    /** Live scale during a pinch. 1f when idle. */
    val scale = Animatable(1f)

    var isPinching: Boolean by mutableStateOf(false)
        private set

    fun onPinchStart() {
        isPinching = true
    }

    /**
     * @param zoom incremental scale factor from the gesture.
     * @param anchorIndex the grid index to keep under the finger across a re-layout.
     */
    fun onPinch(zoom: Float, anchorIndex: Int, gridState: LazyGridState) {
        val next = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        scope.launch { scale.snapTo(next) }

        val target = when {
            next >= COMMIT_IN -> density.zoomedIn
            next <= COMMIT_OUT -> density.zoomedOut
            else -> null
        } ?: return

        commit(target, anchorIndex, gridState)
    }

    fun onPinchEnd() {
        isPinching = false
        scope.launch { scale.animateTo(1f, Motion.zoomSettle()) }
    }

    /** Used by the density buttons, which should animate rather than snap. */
    fun setDensity(target: GridDensity, anchorIndex: Int, gridState: LazyGridState) {
        if (target == density) return
        scope.launch {
            // A short exaggeration before the re-layout sells the change as a zoom rather
            // than a jump-cut.
            scale.animateTo(if (target.columns < density.columns) 1.12f else 0.9f, Motion.fastSpatial())
            commit(target, anchorIndex, gridState)
            scale.animateTo(1f, Motion.spatial())
        }
    }

    private fun commit(target: GridDensity, anchorIndex: Int, gridState: LazyGridState) {
        density = target
        onDensityChanged(target)
        // The item list is identical across densities — only the column count changes — so
        // the index of the photo under the finger is stable and scrolling back to it keeps
        // the user exactly where they were.
        scope.launch {
            scale.snapTo(1f)
            gridState.scrollToItem(anchorIndex.coerceAtLeast(0))
        }
    }

    private companion object {
        const val MIN_SCALE = 0.55f
        const val MAX_SCALE = 1.9f

        /** Deliberately asymmetric: zooming in wants less travel than zooming out. */
        const val COMMIT_IN = 1.35f
        const val COMMIT_OUT = 0.72f
    }
}

@Composable
fun rememberGridZoomState(
    initial: GridDensity = GridDensity.Default,
    onDensityChanged: (GridDensity) -> Unit = {},
): GridZoomState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GridZoomState(initial, scope, onDensityChanged) }
}

/**
 * Pinch handling for the grid.
 *
 * Deliberately **not** [androidx.compose.foundation.gestures.detectTransformGestures]:
 * that consumes single-pointer pan, which would silently kill both the grid's own
 * scrolling and the long-press drag-select gesture. This loop only ever consumes events
 * while two or more pointers are down, so one finger still scrolls and long-presses
 * exactly as before, and a second finger arriving is what switches on the zoom.
 */
fun Modifier.pinchToZoom(
    zoomState: GridZoomState,
    gridState: LazyGridState,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else pointerInput(zoomState, gridState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        do {
            val event = awaitPointerEvent()
            val down = event.changes.count { it.pressed }
            if (down >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    if (!pinching) {
                        pinching = true
                        zoomState.onPinchStart()
                    }
                    zoomState.onPinch(zoom, gridState.firstVisibleItemIndex, gridState)
                    // Only consumed on multi-touch, so single-finger gestures below this
                    // modifier are completely unaffected.
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) zoomState.onPinchEnd()
    }
}
