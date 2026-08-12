package com.galleryorganizer.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galleryorganizer.domain.scrub.ScrubStop
import com.galleryorganizer.domain.scrub.ScrubberModel
import com.galleryorganizer.domain.scrub.YearTick
import com.galleryorganizer.ui.theme.Motion

/**
 * The date slider down the right edge.
 *
 * A 150,000-item library is well over a thousand screens of grid. No amount of flinging
 * makes "take me to spring 2019" reasonable, so this is the answer to that question rather
 * than a decoration on scrolling.
 *
 * Three decisions shape how it feels:
 *
 * **The rail is allocated by photo, not by month.** A month you barely shot gets a sliver
 * and the summer you took nine thousand pictures gets a band you can aim at. One equal slot
 * per month would spend most of the rail's length on the months with nothing in them.
 *
 * **It follows the grid by date, not by index.** The thumb's position comes from the date of
 * the topmost visible photo, so it stays honest whatever paging is doing underneath — which
 * matters here, because a list index in this grid is not a position in the query.
 *
 * **It appears only when it is wanted.** Permanent chrome down the edge of a photo grid is a
 * permanent distraction, so it fades in while the grid is moving and fades out again shortly
 * after it stops.
 */
@Composable
fun DateScrubber(
    model: ScrubberModel,
    /** Where the grid is now, 0..1, derived from the topmost visible photo's date. */
    position: Float,
    visible: Boolean,
    onJump: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!model.isUsable) return

    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(position) }
    var lastStop by remember { mutableStateOf<ScrubStop?>(null) }

    // While a finger is down the thumb follows the finger; otherwise it follows the grid.
    val shown = if (dragging) dragFraction else position
    val thumbFraction by animateFloatAsState(
        targetValue = shown,
        // No spring while dragging: the thumb has to sit under the finger, and a spring
        // would leave it trailing by a few pixels the whole way down.
        animationSpec = if (dragging) Motion.fastEffects() else Motion.spatial(),
        label = "scrubberThumb",
    )
    val activeStop = model.stopAt(shown)

    // When the grid moves on its own, the thumb should be following it, not sitting where
    // the last drag left it.
    LaunchedEffect(position) { if (!dragging) dragFraction = position }

    AnimatedVisibility(
        visible = visible || dragging,
        enter = fadeIn(Motion.effects()) + slideInHorizontally(Motion.spatial()) { it },
        exit = fadeOut(Motion.slowEffects()) + slideOutHorizontally(Motion.spatial()) { it },
        modifier = modifier,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .width(SCRUBBER_WIDTH)
                .pointerInput(model) {
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            dragFraction = (start.y / size.height).coerceIn(0f, 1f)
                            lastStop = model.stopAt(dragFraction)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onVerticalDrag = { change, _ ->
                            dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            val stop = model.stopAt(dragFraction)
                            // A tick as each month goes by, so the months can be felt as
                            // well as read — the finger is covering part of the screen.
                            if (stop != null && stop != lastStop) {
                                lastStop = stop
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            onJump(dragFraction)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            val travel = maxHeight - THUMB_HEIGHT
            val thumbTop = travel * thumbFraction

            YearRail(model = model, dragging = dragging, travel = travel)

            Thumb(
                description = activeStop?.fullLabel,
                expanded = dragging,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = thumbTop, end = 6.dp),
            )

            // The bubble sits clear of the rail so the finger never covers the month it is
            // about to choose.
            AnimatedVisibility(
                visible = dragging,
                enter = fadeIn(Motion.fastEffects()) +
                    scaleIn(Motion.expressiveSpatial(), initialScale = 0.8f),
                exit = fadeOut(Motion.fastEffects()) +
                    scaleOut(Motion.spatial(), targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = thumbTop, end = SCRUBBER_WIDTH),
            ) {
                MonthBubble(activeStop)
            }
        }
    }
}

/**
 * Year labels down the rail, each at the fraction where that year's photos begin.
 *
 * With fifteen years in a phone-height rail there is not room for every label, so crowded
 * ones are dropped rather than drawn on top of each other.
 */
@Composable
private fun BoxWithConstraintsScope.YearRail(
    model: ScrubberModel,
    dragging: Boolean,
    travel: Dp,
) {
    val labelAlpha by animateFloatAsState(
        if (dragging) 1f else 0.5f,
        Motion.effects(),
        label = "yearAlpha",
    )

    val ticks = remember(model, travel) {
        val minGap = if (travel > 0.dp) MIN_YEAR_GAP / travel else 1f
        val kept = ArrayList<YearTick>()
        model.yearTicks.forEach { tick ->
            if (kept.isEmpty() || tick.fraction - kept.last().fraction >= minGap) kept += tick
        }
        kept
    }

    ticks.forEach { tick ->
        Text(
            tick.year.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = travel * tick.fraction + YEAR_LABEL_NUDGE, end = 26.dp)
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}

@Composable
private fun Thumb(description: String?, expanded: Boolean, modifier: Modifier = Modifier) {
    val width by animateDpAsState(
        if (expanded) THUMB_WIDTH_ACTIVE else THUMB_WIDTH,
        Motion.expressiveSpatial(),
        label = "thumbWidth",
    )
    Box(
        modifier
            .size(width = width, height = THUMB_HEIGHT)
            .clip(CircleShape)
            .background(
                if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                },
            )
            .semantics { description?.let { contentDescription = "Jump to date, at $it" } },
        contentAlignment = Alignment.Center,
    ) {
        if (expanded) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                repeat(3) {
                    Box(
                        Modifier
                            .padding(vertical = 1.dp)
                            .size(width = 10.dp, height = 1.5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthBubble(stop: ScrubStop?) {
    if (stop == null) return
    Column(
        Modifier
            .clip(RoundedCornerShape(percent = 45))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            stop.fullLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            "%,d %s".format(stop.itemCount, if (stop.itemCount == 1) "photo" else "photos"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
        )
    }
}

private val SCRUBBER_WIDTH = 56.dp
private val THUMB_WIDTH = 6.dp
private val THUMB_WIDTH_ACTIVE = 26.dp
private val THUMB_HEIGHT = 48.dp

/** Centres a year label against the middle of the thumb rather than its top edge. */
private val YEAR_LABEL_NUDGE = 16.dp

/** Least vertical distance between two year labels before the later one is dropped. */
private val MIN_YEAR_GAP = 34.dp
