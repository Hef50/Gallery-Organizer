package com.galleryorganizer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sell
import androidx.compose.foundation.layout.Column
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
import com.galleryorganizer.ui.grid.GalleryScreen
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.permissions.MediaPermissionScreen
import com.galleryorganizer.ui.permissions.PartialAccessBanner
import com.galleryorganizer.ui.permissions.rememberMediaPermissionController
import com.galleryorganizer.ui.permissions.rememberMediaPermissionState
import com.galleryorganizer.ui.search.FilterSheet
import com.galleryorganizer.ui.search.GallerySearchBar
import com.galleryorganizer.ui.search.SavedSearchRow
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
}

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

    // A catch-up pass, not a rescan: the indexer only reads past its watermark, so on a
    // quiet day this reads a handful of rows.
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
            var sheetOpen by remember { mutableStateOf(false) }
            var filtersOpen by remember { mutableStateOf(false) }
            val query by galleryViewModel.query.collectAsStateWithLifecycle()
            val saved by galleryViewModel.savedSearches.collectAsStateWithLifecycle()
            val activeSaved by galleryViewModel.activeSavedSearch.collectAsStateWithLifecycle()
            val buckets by galleryViewModel.buckets.collectAsStateWithLifecycle()

            LaunchedEffect(lastAction) {
                val action = lastAction ?: return@LaunchedEffect
                val verb = if (action.wasApplied) "Tagged" else "Removed"
                val result = snackbarHostState.showSnackbar(
                    message = "$verb %,d item%s · %s".format(
                        action.mediaIds.size,
                        if (action.mediaIds.size == 1) "" else "s",
                        action.tagName,
                    ),
                    actionLabel = "Undo",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    tagViewModel.undoLast()
                } else {
                    tagViewModel.consumeUndo()
                }
            }

            GalleryScreen(
                viewModel = galleryViewModel,
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
                searchBar = {
                    Column {
                        GallerySearchBar(
                            query = query,
                            onTextChange = galleryViewModel::setText,
                            onOpenFilters = { filtersOpen = true },
                        )
                        SavedSearchRow(
                            saved = saved,
                            activeId = activeSaved?.id,
                            canSave = !query.isEmpty && activeSaved == null,
                            onOpen = galleryViewModel::openSavedSearch,
                            onSave = galleryViewModel::saveCurrentSearch,
                            onTogglePin = {
                                galleryViewModel.setSavedSearchPinned(it.id, !it.pinned)
                            },
                            onDelete = { galleryViewModel.deleteSavedSearch(it.id) },
                        )
                    }
                },
                selectionActions = {
                    IconButton(onClick = { sheetOpen = true }) {
                        Icon(Icons.Filled.Sell, contentDescription = "Tag selected items")
                    }
                },
                topBarActions = {
                    if (!query.isEmpty) {
                        IconButton(onClick = galleryViewModel::selectAllResults) {
                            Icon(
                                Icons.Filled.SelectAll,
                                contentDescription = "Select all results",
                            )
                        }
                    }
                    IconButton(onClick = { navController.navigate(Routes.TAGS) }) {
                        Icon(Icons.Filled.Sell, contentDescription = "Manage tags")
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
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

            if (sheetOpen && selection.active) {
                BulkTagSheet(
                    selection = selection.selected,
                    viewModel = tagViewModel,
                    onDismiss = { sheetOpen = false },
                )
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
    }
}
