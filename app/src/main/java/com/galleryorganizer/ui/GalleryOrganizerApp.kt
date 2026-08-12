package com.galleryorganizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.permissions.MediaAccess
import com.galleryorganizer.permissions.MediaPermissionState
import com.galleryorganizer.ui.albums.AddToAlbumSheet
import com.galleryorganizer.ui.albums.AlbumDetailScreen
import com.galleryorganizer.ui.albums.AlbumViewModel
import com.galleryorganizer.ui.albums.AlbumsScreen
import com.galleryorganizer.ui.components.GalleryNavBar
import com.galleryorganizer.ui.components.GallerySection
import com.galleryorganizer.ui.duplicates.DuplicatesScreen
import com.galleryorganizer.ui.duplicates.DuplicatesViewModel
import com.galleryorganizer.ui.grid.GalleryViewModel
import com.galleryorganizer.ui.permissions.MediaPermissionScreen
import com.galleryorganizer.ui.permissions.PartialAccessBanner
import com.galleryorganizer.ui.permissions.rememberMediaPermissionController
import com.galleryorganizer.ui.permissions.rememberMediaPermissionState
import com.galleryorganizer.ui.places.PlacesScreen
import com.galleryorganizer.ui.places.PlacesViewModel
import com.galleryorganizer.ui.search.FilterSheet
import com.galleryorganizer.ui.search.GalleryHeader
import com.galleryorganizer.ui.search.SectionHeader
import com.galleryorganizer.ui.settings.FoldersScreen
import com.galleryorganizer.ui.settings.SettingsScreen
import com.galleryorganizer.ui.settings.SettingsViewModel
import com.galleryorganizer.ui.suggestions.SuggestionsScreen
import com.galleryorganizer.ui.suggestions.SuggestionsViewModel
import com.galleryorganizer.ui.tags.BulkTagSheet
import com.galleryorganizer.ui.tags.TagManagerScreen
import com.galleryorganizer.ui.tags.TagViewModel
import com.galleryorganizer.ui.theme.Motion
import com.galleryorganizer.work.IndexingStatus
import com.galleryorganizer.work.WorkScheduler
import kotlinx.coroutines.launch

object Routes {
    const val GALLERY = "gallery"
    const val ALBUMS = "albums"
    const val ALBUM_DETAIL = "album-detail"
    const val PLACES = "places"
    const val TAGS = "tags"
    const val SETTINGS = "settings"
    const val SUGGESTIONS = "suggestions"
    const val DUPLICATES = "duplicates"
    const val FOLDERS = "folders"
}

/** Where the floating nav bar sits, so every screen can keep its content clear of it. */
private val NavBarInset = PaddingValues(bottom = 78.dp)

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
        // Coordinates are what the Places screen runs on, and reading them is a second pass
        // over the files, so it starts as soon as there is anything to read rather than the
        // first time someone opens the map and finds it empty.
        WorkScheduler.enqueueLocationBackfill(context)
    }

    val navController = rememberNavController()
    val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory(container))
    val tagViewModel: TagViewModel = viewModel(factory = TagViewModel.Factory(container))
    val albumViewModel: AlbumViewModel = viewModel(factory = AlbumViewModel.Factory(container))

    val snackbarHostState = remember { SnackbarHostState() }
    var tagSheetFor by remember { mutableStateOf<Set<Long>?>(null) }
    var albumSheetFor by remember { mutableStateOf<Set<Long>?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }

    val backEntry by navController.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    val section = GallerySection.entries.firstOrNull { it.route == route }

    val lastTagAction by tagViewModel.lastAction.collectAsStateWithLifecycle()
    val lastAlbumAction by albumViewModel.lastAction.collectAsStateWithLifecycle()

    LaunchedEffect(lastTagAction) {
        val action = lastTagAction ?: return@LaunchedEffect
        val verb = if (action.wasApplied) "Tagged" else "Removed"
        val result = snackbarHostState.showSnackbar(
            message = "$verb %,d · %s".format(action.mediaIds.size, action.tagName),
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) tagViewModel.undoLast()
        else tagViewModel.consumeUndo()
    }

    LaunchedEffect(lastAlbumAction) {
        val action = lastAlbumAction ?: return@LaunchedEffect
        val verb = if (action.wasAdded) "Added to" else "Removed from"
        val result = snackbarHostState.showSnackbar(
            message = "$verb %s · %,d".format(action.albumName, action.mediaIds.size),
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) albumViewModel.undoLast()
        else albumViewModel.consumeUndo()
    }

    ViewerHost(
        galleryViewModel = galleryViewModel,
        tagViewModel = tagViewModel,
        onTagOne = { media -> tagSheetFor = setOf(media.id) },
    ) { sharedScope, animatedScope ->
        Box(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = Routes.GALLERY) {
                composable(Routes.GALLERY) {
                    val indexing by remember(context) { WorkScheduler.observeIndexing(context) }
                        .collectAsStateWithLifecycle(IndexingStatus())
                    val selection by galleryViewModel.selection.collectAsStateWithLifecycle()
                    val query by galleryViewModel.query.collectAsStateWithLifecycle()
                    val saved by galleryViewModel.savedSearches.collectAsStateWithLifecycle()
                    val activeSaved by galleryViewModel.activeSavedSearch.collectAsStateWithLifecycle()
                    val total by galleryViewModel.itemCount.collectAsStateWithLifecycle()

                    GalleryHome(
                        galleryViewModel = galleryViewModel,
                        indexing = indexing,
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
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
                                onTogglePin = {
                                    galleryViewModel.setSavedSearchPinned(it.id, !it.pinned)
                                },
                                onSelectAll = galleryViewModel::selectAllResults,
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        },
                        selectionActions = { selected ->
                            IconButton(onClick = { albumSheetFor = selected }) {
                                Icon(Icons.Rounded.PhotoAlbum, contentDescription = "Add to an album")
                            }
                            IconButton(onClick = { tagSheetFor = selected }) {
                                Icon(Icons.Rounded.Sell, contentDescription = "Tag selected items")
                            }
                        },
                        contentPadding = NavBarInset,
                    )

                    if (filtersOpen) {
                        val buckets by galleryViewModel.buckets.collectAsStateWithLifecycle()
                        FilterSheet(
                            query = query,
                            buckets = buckets,
                            tagViewModel = tagViewModel,
                            onApply = galleryViewModel::setQuery,
                            onDismiss = { filtersOpen = false },
                        )
                    }

                    // A selection that empties itself should close the sheets with it.
                    LaunchedEffect(selection.active) {
                        if (!selection.active) {
                            if (tagSheetFor?.size != 1) tagSheetFor = null
                            albumSheetFor = null
                        }
                    }
                }

                composable(Routes.ALBUMS) {
                    AlbumsScreen(
                        viewModel = albumViewModel,
                        onOpen = { album ->
                            albumViewModel.open(album.id)
                            navController.navigate(Routes.ALBUM_DETAIL)
                        },
                        header = {
                            SectionHeader(
                                title = "Albums",
                                subtitle = "Sequences you put together yourself",
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        },
                        contentPadding = NavBarInset,
                    )
                }

                composable(Routes.ALBUM_DETAIL) {
                    val openId by albumViewModel.openAlbumId.collectAsStateWithLifecycle()
                    AlbumDetailScreen(
                        viewModel = albumViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenItem = { media, index ->
                            // Swiping stays inside the album, in the album's order.
                            openId?.let { galleryViewModel.openAlbumViewer(it, media.id, index) }
                        },
                        contentPadding = NavBarInset,
                    )
                }

                composable(Routes.PLACES) {
                    val placesViewModel: PlacesViewModel =
                        viewModel(factory = PlacesViewModel.Factory(container))
                    PlacesScreen(
                        viewModel = placesViewModel,
                        header = {
                            SectionHeader(
                                title = "Places",
                                subtitle = "Everything with a location on it",
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        },
                        // Swiping stays inside the cluster the user tapped.
                        onOpenItem = { media, cluster ->
                            galleryViewModel.openIdsViewer(cluster, media.id)
                        },
                        onTagCluster = { ids -> if (ids.isNotEmpty()) tagSheetFor = ids },
                        contentPadding = NavBarInset,
                    )
                }

                composable(Routes.TAGS) {
                    TagManagerScreen(
                        viewModel = tagViewModel,
                        header = {
                            SectionHeader(
                                title = "Tags",
                                subtitle = "People, places, events and things",
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        },
                        contentPadding = NavBarInset,
                    )
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

            // The bar is only shown on the four top-level sections. On a detail screen it would
            // be an invitation to lose your place.
            AnimatedVisibility(
                visible = section != null,
                enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { it },
                exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                GalleryNavBar(
                    selected = section ?: GallerySection.Library,
                    onSelect = { target ->
                        if (target.route != route) {
                            navController.navigate(target.route) {
                                popUpTo(Routes.GALLERY) { inclusive = target == GallerySection.Library }
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }

            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
            )
        }
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

    albumSheetFor?.let { target ->
        if (target.isNotEmpty()) {
            AddToAlbumSheet(
                selection = target,
                viewModel = albumViewModel,
                onDismiss = { albumSheetFor = null },
            )
        }
    }

    // Back from a top-level section that is not the library returns to the library rather
    // than closing the app — the same expectation every tabbed app sets.
    BackHandler(enabled = section != null && section != GallerySection.Library) {
        navController.navigate(Routes.GALLERY) {
            popUpTo(Routes.GALLERY) { inclusive = true }
            launchSingleTop = true
        }
    }
}
