package com.galleryorganizer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.permissions.MediaAccess
import com.galleryorganizer.ui.permissions.MediaPermissionScreen
import com.galleryorganizer.ui.permissions.rememberMediaPermissionController
import com.galleryorganizer.ui.permissions.rememberMediaPermissionState
import kotlinx.coroutines.launch

@Composable
fun GalleryOrganizerApp() {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val liveState by rememberMediaPermissionState()
    // The launcher result is authoritative the instant the dialog closes; the lifecycle
    // observer only catches changes made outside the app.
    var overrideState by remember { mutableStateOf<com.galleryorganizer.permissions.MediaPermissionState?>(null) }
    val permissions = overrideState ?: liveState

    val hasAsked by container.settings.hasAskedMediaPermission.collectAsStateWithLifecycle(false)

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

        MediaAccess.Partial, MediaAccess.Full -> Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Gallery Organizer")
            }
        }
    }
}
