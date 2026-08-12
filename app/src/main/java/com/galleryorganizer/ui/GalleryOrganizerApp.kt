package com.galleryorganizer.ui

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.permissions.MediaAccess
import com.galleryorganizer.permissions.MediaPermissionState
import com.galleryorganizer.ui.duplicates.DuplicatesScreen
import com.galleryorganizer.ui.duplicates.DuplicatesViewModel
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.permissions.MediaPermissionScreen
import com.galleryorganizer.ui.permissions.PartialAccessBanner
import com.galleryorganizer.ui.permissions.rememberMediaPermissionController
import com.galleryorganizer.ui.permissions.rememberMediaPermissionState
import com.galleryorganizer.ui.search.FilterSheet
import com.galleryorganizer.ui.search.GalleryHeader
import com.galleryorganizer.ui.settings.FoldersScreen
import com.galleryorganizer.ui.settings.SettingsScreen
import com.galleryorganizer.ui.settings.SettingsViewModel
import com.galleryorganizer.ui.suggestions.SuggestionsScreen
import com.galleryorganizer.ui.suggestions.SuggestionsViewModel
import com.galleryorganizer.ui.tags.BulkTagSheet
import com.galleryorganizer.ui.tags.TagManagerScreen
import com.galleryorganizer.ui.tags.TagViewModel
import com.galleryorganizer.work.IndexingStatus
import com.galleryorganizer.work.WorkScheduler
import kotlinx.coroutines.launch

object Routes {
    const val GALLERY = "gallery"
    const val TAGS = "tags"
    const val SETTINGS = "settings"
    const val SUGGESTIONS = "suggestions"
    const val DUPLICATES = "duplicates"
    const val FOLDERS = "folders"
}

@Composable
fun GalleryOrganizerApp() {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val liveState by rememberMediaPermissionState()
    var overrideState by remember { mutableStateOf<MediaPermissionState?>(null) }
    val permissions = overrideState ?: liveState

    val hasAsked by container.settings.hasAskedMediaPermission.collectAsStateWithLifecycle(false)
    val bannerDismissed by container.settings.partialAccessBannerDismissed
        .collectAsStateWithLifecycle(false)

    val controller = rememberMediaPermissionController(onResult = { overrideState = it })

    LaunchedEffect(liveState) { overrideState = null }

    if (permissions.access == MediaAccess.None) {
        MediaPermissionScreen(
            hasAskedBefore = hasAsked,
            canShowRationale = controller.canShowRationale,
            onRequest = {
                scope.launch { container.settings.setHasAskedMediaPermission(true) }
                controller.request()
            },
            onOpenSettings = controller::openAppSettings,
        )
        return
    }

    LaunchedEffect(permissions.access) {
        WorkScheduler.enqueueIndex(context)
        WorkScheduler.enqueuePeriodicIndex(context)
    }

    val navController = rememberNavController()
    val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory(container))
    val tagViewModel: TagViewModel = viewModel(factory = TagViewModel.Factory(container))

    NavHost(navController, startDestination = Routes.GALLERY) {
        composable(Routes.GALLERY) {
            val indexing by remember(context) { WorkScheduler.observeIndexing(context) }
                .collectAsStateWithLifecycle(IndexingStatus())
            val selection by galleryViewModel.selection.collectAsStateWithLifecycle()
            val lastAction by tagViewModel.lastAction.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            var tagSheetFor by remember { mutableStateOf<Set<Long>?>(null) }
            var filtersOpen by remember { mutableStateOf(false) }
            val query by galleryViewModel.query.collectAsStateWithLifecycle()
            val saved by galleryViewModel.savedSearches.collectAsStateWithLifecycle()
            val activeSaved by galleryViewModel.activeSavedSearch.collectAsStateWithLifecycle()
            val buckets by galleryViewModel.buckets.collectAsStateWithLifecycle()
            val total by galleryViewModel.itemCount.collectAsStateWithLifecycle()

            LaunchedEffect(lastAction) {
                val action = lastAction ?: return@LaunchedEffect
                val verb = if (action.wasApplied) "Tagged" else "Removed"
                val result = snackbarHostState.showSnackbar(
                    message = "$verb %,d · %s".format(action.mediaIds.size, action.tagName),
                    actionLabel = "Undo",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    tagViewModel.undoLast()
                } else {
                    tagViewModel.consumeUndo()
                }
            }

            GalleryHome(
                galleryViewModel = galleryViewModel,
                tagViewModel = tagViewModel,
                indexing = indexing,
                snackbarHostState = snackbarHostState,
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
                header = {
                    GalleryHeader(
                        query = query,
                        total = total,
                        savedSearches = saved,
                        activeSavedId = activeSaved?.id,
                        onTextChange = galleryViewModel::setText,
                        onOpenFilters = { filtersOpen = true },
                        onQuickFilter = galleryViewModel::applyQuickFilter,
                        onOpenSaved = galleryViewModel::openSavedSearch,
                        onSaveSearch = galleryViewModel::saveCurrentSearch,
                        onDeleteSaved = { galleryViewModel.deleteSavedSearch(it.id) },
                        onTogglePin = { galleryViewModel.setSavedSearchPinned(it.id, !it.pinned) },
                        onSelectAll = galleryViewModel::selectAllResults,
                        onOpenTags = { navController.navigate(Routes.TAGS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                },
                selectionActions = { selected ->
                    IconButton(onClick = { tagSheetFor = selected }) {
                        Icon(Icons.Rounded.Sell, contentDescription = "Tag selected items")
                    }
                },
                onTagOne = { media -> tagSheetFor = setOf(media.id) },
            )

            if (filtersOpen) {
                FilterSheet(
                    query = query,
                    buckets = buckets,
                    tagViewModel = tagViewModel,
                    onApply = galleryViewModel::setQuery,
                    onDismiss = { filtersOpen = false },
                )
            }

            tagSheetFor?.let { target ->
                if (target.isNotEmpty()) {
                    BulkTagSheet(
                        selection = target,
                        viewModel = tagViewModel,
                        onDismiss = { tagSheetFor = null },
                    )
                }
            }

            // A selection that empties itself should close the sheet with it.
            LaunchedEffect(selection.active) {
                if (!selection.active && tagSheetFor?.size != 1) tagSheetFor = null
            }
        }

        composable(Routes.TAGS) {
            TagManagerScreen(viewModel = tagViewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel =
                viewModel(factory = SettingsViewModel.Factory(container))
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenSuggestions = { navController.navigate(Routes.SUGGESTIONS) },
                onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
                onOpenFolders = { navController.navigate(Routes.FOLDERS) },
            )
        }

        composable(Routes.SUGGESTIONS) {
            val suggestionsViewModel: SuggestionsViewModel =
                viewModel(factory = SuggestionsViewModel.Factory(container))
            SuggestionsScreen(
                viewModel = suggestionsViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DUPLICATES) {
            val duplicatesViewModel: DuplicatesViewModel =
                viewModel(factory = DuplicatesViewModel.Factory(container))
            DuplicatesScreen(
                viewModel = duplicatesViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.FOLDERS) {
            val buckets by galleryViewModel.buckets.collectAsStateWithLifecycle()
            FoldersScreen(
                buckets = buckets,
                hiddenIds = container.settings.hiddenBucketIds,
                settings = container.settings,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
