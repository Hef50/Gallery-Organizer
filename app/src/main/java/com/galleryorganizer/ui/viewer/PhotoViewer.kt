package com.galleryorganizer.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.theme.Motion
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Full-screen photo viewer.
 *
 * Opens as a **shared element** from the grid cell the user tapped: the thumbnail's bounds
 * animate into the full-bleed image rather than the screen cutting or sliding. That single
 * detail is most of the difference between an app that feels assembled and one that feels
 * designed, because it preserves the user's sense of where the photo *is*.
 *
 * Closing is a downward drag. The photo follows the finger, shrinking and fading the black
 * behind it as it goes, so releasing halfway springs it back rather than committing to a
 * decision the user did not finish making.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoViewer(
    entries: LazyPagingItems<MediaEntity>,
    initialIndex: Int,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    onTagRequested: (MediaEntity) -> Unit,
    onOpenExternally: (MediaEntity) -> Unit,
    tagNamesFor: (Long) -> List<String>,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceAtLeast(0),
        pageCount = { entries.itemCount },
    )

    var chromeVisible by remember { mutableStateOf(true) }
    var infoVisible by remember { mutableStateOf(false) }

    // How far the dismiss drag has travelled, in pixels. Drives scale, alpha and the
    // background scrim together so the whole screen responds as one object.
    val dragY = remember { Animatable(0f) }
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }
    val dismissProgress = (abs(dragY.value) / (dismissThreshold * 2.2f)).coerceIn(0f, 1f)

    val current = entries.peekOrNull(pagerState.currentPage)

    Box(
        Modifier
            .fillMaxSize()
            // The ground fades with the drag so the grid shows through as you pull down —
            // it reads as putting the photo back, not as closing a window.
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.85f)),
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = entries.peekOrNull(page)
            if (media == null) {
                Box(Modifier.fillMaxSize())
                return@HorizontalPager
            }

            val zoomState = rememberZoomState()

            // Leaving a page zoomed and coming back to it later is disorienting; every
            // page starts fitted.
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != page) zoomState.resetImmediately()
            }

            ZoomableContent(
                state = zoomState,
                onTap = { chromeVisible = !chromeVisible },
                onDragDismiss = { y -> scope.launch { dragY.snapTo(y) } },
                onDragDismissEnd = { velocity ->
                    scope.launch {
                        if (abs(dragY.value) > dismissThreshold || abs(velocity) > 28f) {
                            onClose()
                        } else {
                            dragY.animateTo(0f, Motion.spatial())
                        }
                    }
                },
                modifier = Modifier.graphicsLayer {
                    translationY = if (page == pagerState.currentPage) dragY.value else 0f
                    val shrink = 1f - dismissProgress * 0.25f
                    if (page == pagerState.currentPage) {
                        scaleX = shrink
                        scaleY = shrink
                    }
                },
            ) {
                with(sharedScope) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(media.uri)
                            // Full resolution here, unlike the grid: this is the one place
                            // the user is actually looking at the photograph.
                            .size(CoilSize.ORIGINAL)
                            .crossfade(true)
                            .build(),
                        contentDescription = media.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .sharedElement(
                                rememberSharedContentState(key = ViewerSharedKey(media.id)),
                                animatedVisibilityScope = animatedScope,
                                boundsTransform = { _, _ -> Motion.heroBounds },
                            ),
                    )
                }
            }
        }

        ViewerChrome(
            visible = chromeVisible && dismissProgress < 0.05f,
            media = current,
            position = pagerState.currentPage + 1,
            total = entries.itemCount,
            onClose = onClose,
            onTag = { current?.let(onTagRequested) },
            onInfo = { infoVisible = !infoVisible },
            onPlay = { current?.let(onOpenExternally) },
        )

        AnimatedVisibility(
            visible = infoVisible && current != null,
            enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { it / 3 },
            exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { MediaInfoPanel(it, tagNamesFor(it.id)) }
        }
    }

    // Keep Coil warm one page either side so a swipe never lands on a grey rectangle.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            listOf(page - 1, page + 1).forEach { entries.peekOrNull(it) }
        }
    }
}

/** Distinct key type so a shared-element match can never collide with another screen's. */
data class ViewerSharedKey(val mediaId: Long)

@Composable
private fun ViewerChrome(
    visible: Boolean,
    media: MediaEntity?,
    position: Int,
    total: Int,
    onClose: () -> Unit,
    onTag: () -> Unit,
    onInfo: () -> Unit,
    onPlay: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { -it },
        exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { -it },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val taken = media?.dateTaken?.let { Instant.ofEpochMilli(it).atZone(zone) }
                Text(
                    taken?.format(dateFormat) ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Text(
                    listOfNotNull(
                        taken?.format(timeFormat),
                        media?.bucketName?.takeIf { it.isNotBlank() },
                        if (total > 0) "$position of ${"%,d".format(total)}" else null,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { it },
        exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { it },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassButton(onClick = onTag) {
                    Icon(Icons.Rounded.Sell, contentDescription = "Tag this", tint = Color.White)
                }
                if (media?.isVideo == true) {
                    GlassButton(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
                    }
                }
                GlassButton(onClick = onInfo) {
                    Icon(Icons.Rounded.Info, contentDescription = "Details", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Chrome over a photograph has to be legible against both a white sky and a black shadow.
 * A translucent dark disc does that without a hard-edged bar stealing a strip of the image.
 */
@Composable
private fun GlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null
