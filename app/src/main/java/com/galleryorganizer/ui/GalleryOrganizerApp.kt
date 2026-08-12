package com.galleryorganizer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.permissions.MediaAccess
import com.galleryorganizer.permissions.MediaPermissionState
import com.galleryorganizer.ui.grid.GalleryScreen
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.permissions.MediaPermissionScreen
import com.galleryorganizer.ui.permissions.PartialAccessBanner
import com.galleryorganizer.ui.permissions.rememberMediaPermissionController
import com.galleryorganizer.ui.permissions.rememberMediaPermissionState
import com.galleryorganizer.work.IndexingStatus
import com.galleryorganizer.work.WorkScheduler
import kotlinx.coroutines.launch

@Composable
fun GalleryOrganizerApp() {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val liveState by rememberMediaPermissionState()
    // The launcher result is authoritative the instant the dialog closes; the lifecycle
    // observer only catches changes made outside the app.
    var overrideState by remember { mutableStateOf<MediaPermissionState?>(null) }
    val permissions = overrideState ?: liveState

    val hasAsked by container.settings.hasAskedMediaPermission.collectAsStateWithLifecycle(false)
    val bannerDismissed by container.settings.partialAccessBannerDismissed
        .collectAsStateWithLifecycle(false)

    val controller = rememberMediaPermissionController(onResult = { overrideState = it })

    LaunchedEffect(liveState) { overrideState = null }

    when (permissions.access) {
        MediaAccess.None -> MediaPermissionScreen(
            hasAskedBefore = hasAsked,
            canShowRationale = controller.canShowRationale,
            onRequest = {
                scope.launch { container.settings.setHasAskedMediaPermission(true) }
                controller.request()
            },
            onOpenSettings = controller::openAppSettings,
        )

        MediaAccess.Partial, MediaAccess.Full -> {
            // A catch-up pass, not a rescan: the indexer only reads past its watermark,
            // so on a quiet day this reads a handful of rows.
            LaunchedEffect(permissions.access) {
                WorkScheduler.enqueueIndex(context)
                WorkScheduler.enqueuePeriodicIndex(context)
            }

            val indexing by remember(context) { WorkScheduler.observeIndexing(context) }
                .collectAsStateWithLifecycle(IndexingStatus())

            val viewModel: GalleryViewModel = viewModel(
                factory = GalleryViewModel.Factory(container),
            )

            GalleryScreen(
                viewModel = viewModel,
                indexing = indexing,
                banner = {
                    if (permissions.access == MediaAccess.Partial && !bannerDismissed) {
                        PartialAccessBanner(
                            onManageSelection = controller::request,
                            onGrantAll = controller::openAppSettings,
                            onDismiss = {
                                scope.launch {
                                    container.settings.setPartialAccessBannerDismissed(true)
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}
