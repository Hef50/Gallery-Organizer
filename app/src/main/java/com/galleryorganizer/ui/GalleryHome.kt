package com.galleryorganizer.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.grid.GalleryScreen
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.tags.TagViewModel
import com.galleryorganizer.ui.theme.Motion
import com.galleryorganizer.ui.viewer.PhotoViewer
import com.galleryorganizer.work.IndexingStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's content and the full-screen viewer, sharing one transition scope.
 *
 * They live in a single [AnimatedContent] rather than as two navigation destinations
 * because that is what lets the tapped thumbnail *become* the full-screen photo. Routing
 * the viewer through the nav graph would work, but the shared element then has to survive
 * a destination change, and back-stack restoration during the transition makes it flicker.
 * One state flip, one transition, no flicker.
 *
 * It wraps the *whole* shell rather than just the grid so that an album or a map cluster can
 * open the viewer too, with the same hero transition, without each screen hosting its own
 * copy of it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ViewerHost(
    galleryViewModel: GalleryViewModel,
    tagViewModel: TagViewModel,
    onTagOne: (MediaEntity) -> Unit,
    content: @Composable (SharedTransitionScope, AnimatedVisibilityScope) -> Unit,
) {
    val context = LocalContext.current
    val viewerRequest by galleryViewModel.viewer.collectAsStateWithLifecycle()

    SharedTransitionLayout(Modifier) {
        AnimatedContent(
            targetState = viewerRequest,
            transitionSpec = {
                // The photo itself carries the motion via the shared element, so the rest
                // of each screen only needs to get out of the way.
                fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects())
            },
            contentKey = { it?.mediaId },
            label = "contentToViewer",
        ) { request ->
            if (request == null) {
                content(this@SharedTransitionLayout, this@AnimatedContent)
            } else {
                val viewerEntries = galleryViewModel.viewerEntries.collectAsLazyPagingItems()
                val tagCache = remember { MutableStateFlow<Map<Long, List<String>>>(emptyMap()) }
                val tags by tagCache.collectAsStateWithLifecycle()

                BackHandler { galleryViewModel.closeViewer() }

                PhotoViewer(
                    entries = viewerEntries,
                    // Zero, not request.mediaIndex: the Pager was built with
                    // initialKey = mediaIndex, and with placeholders disabled the loaded
                    // window starts *at* that row. Index 0 of the window is the photo the
                    // user tapped.
                    initialIndex = 0,
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@AnimatedContent,
                    onClose = galleryViewModel::closeViewer,
                    onTagRequested = onTagOne,
                    onOpenExternally = { media -> context.openExternally(media) },
                    tagNamesFor = { id -> tags[id].orEmpty() },
                )

                // Tag names for the visible photo, fetched lazily so the viewer opens
                // instantly and the chips appear a frame later rather than blocking it.
                LaunchedEffect(request.mediaId) {
                    tagCache.value = tagCache.value +
                        (request.mediaId to tagViewModel.tagNamesFor(request.mediaId))
                }
            }
        }
    }
}

/** The library grid, wired into the transition scopes [ViewerHost] provides. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryHome(
    galleryViewModel: GalleryViewModel,
    indexing: IndexingStatus,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    banner: @Composable () -> Unit,
    header: @Composable () -> Unit,
    selectionActions: @Composable (Set<Long>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    GalleryScreen(
        viewModel = galleryViewModel,
        indexing = indexing,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
        banner = banner,
        header = header,
        onOpen = { media, index -> galleryViewModel.openViewer(media.id, index) },
        selectionActions = selectionActions,
        contentPadding = contentPadding,
    )
}

/**
 * Hands a video to whatever the user normally plays videos with.
 *
 * Bundling a player would mean Media3 and a few more megabytes for something the phone
 * already does well; this app's job is organising, not playback.
 */
private fun android.content.Context.openExternally(media: MediaEntity) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(media.uri), media.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}
