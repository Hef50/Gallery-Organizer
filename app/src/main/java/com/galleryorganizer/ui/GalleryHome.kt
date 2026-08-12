package com.galleryorganizer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.grid.GalleryScreen
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.tags.TagViewModel
import com.galleryorganizer.ui.viewer.PhotoViewer
import com.galleryorganizer.work.IndexingStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Grid and viewer, sharing one transition scope.
 *
 * They live in a single [AnimatedContent] rather than as two navigation destinations
 * because that is what lets the tapped thumbnail *become* the full-screen photo. Routing
 * the viewer through the nav graph would work, but the shared element then has to survive
 * a destination change, and back-stack restoration during the transition makes it flicker.
 * One state flip, one transition, no flicker.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryHome(
    galleryViewModel: GalleryViewModel,
    tagViewModel: TagViewModel,
    indexing: IndexingStatus,
    snackbarHostState: SnackbarHostState,
    banner: @Composable () -> Unit,
    header: @Composable () -> Unit,
    selectionActions: @Composable (Set<Long>) -> Unit,
    onTagOne: (MediaEntity) -> Unit,
) {
    val context = LocalContext.current
    val viewerRequest by galleryViewModel.viewer.collectAsStateWithLifecycle()

    SharedTransitionLayout(Modifier) {
        AnimatedContent(
            targetState = viewerRequest,
            transitionSpec = {
                // The photo itself carries the motion via the shared element, so the rest
                // of each screen only needs to get out of the way.
                fadeIn(com.galleryorganizer.ui.theme.Motion.effects()) togetherWith
                    fadeOut(com.galleryorganizer.ui.theme.Motion.effects())
            },
            contentKey = { it?.mediaId },
            label = "gridToViewer",
        ) { request ->
            if (request == null) {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    indexing = indexing,
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@AnimatedContent,
                    snackbarHostState = snackbarHostState,
                    banner = banner,
                    header = header,
                    onOpen = { media, index -> galleryViewModel.openViewer(media.id, index) },
                    selectionActions = selectionActions,
                )
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
