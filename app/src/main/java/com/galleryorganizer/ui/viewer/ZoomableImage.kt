package com.galleryorganizer.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.galleryorganizer.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pinch-zoom, pan and double-tap state for a single photo.
 *
 * The rules that make this feel right rather than merely functional:
 *
 * - **Zoom is anchored to the centroid.** Scaling about the centre instead makes the point
 *   you are pinching drift away from your fingers, which feels like the image is fighting
 *   you.
 * - **Pan is clamped to the image, not the viewport**, and the clamp is recomputed at every
 *   scale, so you can never drag a zoomed photo off into empty space.
 * - **The pager is only allowed to take the gesture at the edges.** Panning a zoomed photo
 *   must not flick to the next one until you have actually reached its edge — that is the
 *   single most common way a photo viewer feels broken.
 */
@Stable
class ZoomState(private val scope: CoroutineScope) {

    val scale = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    var viewport: Size = Size.Zero
        private set

    /** Intrinsic image size, once known, so the pan clamp matches the *photo* not the box. */
    var contentSize: Size = Size.Zero

    val isZoomed: Boolean get() = scale.value > 1.01f

    fun onViewportChanged(size: IntSize) {
        viewport = Size(size.width.toFloat(), size.height.toFloat())
    }

    /**
     * True when a horizontal drag of [delta] would run past the edge of the zoomed image,
     * which is the moment the pager should take over.
     */
    fun canPanHorizontally(delta: Float): Boolean {
        if (!isZoomed) return false
        val bound = maxOffsetX()
        val next = offsetX.value + delta
        return next in -bound..bound || abs(next) < abs(offsetX.value)
    }

    fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        val next = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)

        // Keep the point under the fingers fixed: shift the offset by how much that point
        // moves as a result of the scale change.
        val factor = next / scale.value
        val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val adjustedX = (offsetX.value - focus.x) * factor + focus.x + pan.x
        val adjustedY = (offsetY.value - focus.y) * factor + focus.y + pan.y

        scope.launch {
            scale.snapTo(next)
            offsetX.snapTo(adjustedX.coerceIn(-maxOffsetX(), maxOffsetX()))
            offsetY.snapTo(adjustedY.coerceIn(-maxOffsetY(), maxOffsetY()))
        }
    }

    fun onGestureEnd() {
        scope.launch {
            if (scale.value < 1.02f) {
                reset()
            } else {
                // Rubber-band back inside the bounds rather than hard-clamping mid-gesture.
                launch { offsetX.animateTo(offsetX.value.coerceIn(-maxOffsetX(), maxOffsetX()), Motion.zoomSettle()) }
                launch { offsetY.animateTo(offsetY.value.coerceIn(-maxOffsetY(), maxOffsetY()), Motion.zoomSettle()) }
            }
        }
    }

    /** Double-tap toggles between fit and a useful magnification, centred on the tap. */
    fun toggleZoom(at: Offset) {
        scope.launch {
            if (isZoomed) {
                reset()
            } else {
                val focus = at - Offset(viewport.width / 2f, viewport.height / 2f)
                val target = DOUBLE_TAP_SCALE
                launch { scale.animateTo(target, Motion.spatial()) }
                val bx = maxOffsetXAt(target)
                val by = maxOffsetYAt(target)
                launch { offsetX.animateTo((-focus.x * (target - 1f)).coerceIn(-bx, bx), Motion.spatial()) }
                launch { offsetY.animateTo((-focus.y * (target - 1f)).coerceIn(-by, by), Motion.spatial()) }
            }
        }
    }

    fun reset() {
        scope.launch { scale.animateTo(1f, Motion.spatial()) }
        scope.launch { offsetX.animateTo(0f, Motion.spatial()) }
        scope.launch { offsetY.animateTo(0f, Motion.spatial()) }
    }

    fun resetImmediately() {
        scope.launch {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    private fun maxOffsetX() = maxOffsetXAt(scale.value)
    private fun maxOffsetY() = maxOffsetYAt(scale.value)

    /**
     * Half the overhang of the scaled *content*. Using the viewport here instead would let
     * a letterboxed photo pan into its own black bars.
     */
    private fun maxOffsetXAt(atScale: Float): Float {
        val width = if (contentSize.width > 0f) contentSize.width else viewport.width
        return ((width * atScale) - viewport.width).coerceAtLeast(0f) / 2f
    }

    private fun maxOffsetYAt(atScale: Float): Float {
        val height = if (contentSize.height > 0f) contentSize.height else viewport.height
        return ((height * atScale) - viewport.height).coerceAtLeast(0f) / 2f
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.6f
    }
}

@Composable
fun rememberZoomState(): ZoomState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ZoomState(scope) }
}

/**
 * Applies zoom/pan gestures and the resulting transform.
 *
 * @param onTap single tap — toggles the viewer's chrome.
 * @param onDragDismiss vertical drag while un-zoomed, for swipe-to-close. Reported as a
 *   fraction so the caller can fade and shrink in step with the finger.
 */
@Composable
fun ZoomableContent(
    state: ZoomState,
    onTap: () -> Unit,
    onDragDismiss: (dragY: Float) -> Unit,
    onDragDismissEnd: (velocityY: Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged(state::onViewportChanged)
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { state.toggleZoom(it) },
                )
            }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragY = 0f
                    var lastY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        if (down >= 2 || state.isZoomed) {
                            state.onGesture(event.calculateCentroid(), pan, zoom)
                            event.changes.forEach { it.consume() }
                        } else if (abs(pan.y) > 0f && abs(pan.y) > abs(pan.x)) {
                            // One finger, not zoomed, moving mostly vertically: this is a
                            // dismiss, and taking it here is what stops the pager from
                            // stealing a downward flick.
                            dragY += pan.y
                            lastY = pan.y
                            onDragDismiss(dragY)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (dragY != 0f) onDragDismissEnd(lastY)
                    state.onGestureEnd()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Read inside the lambda on purpose: graphicsLayer defers it to the draw
                // phase, so a pinch redraws without recomposing a single node.
                .graphicsLayer {
                    scaleX = state.scale.value
                    scaleY = state.scale.value
                    translationX = state.offsetX.value
                    translationY = state.offsetY.value
                },
        ) {
            content()
        }
    }
}
