package com.galleryorganizer.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galleryorganizer.permissions.MediaPermissionState
import com.galleryorganizer.permissions.MediaPermissions

/**
 * Tracks the live media-permission state, re-reading it every time the app resumes so a
 * change made in system Settings is picked up immediately.
 */
@Composable
fun rememberMediaPermissionState(): State<MediaPermissionState> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(MediaPermissions.state(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                state.value = MediaPermissions.state(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Everything the permission gate needs to drive the system dialogs. */
class MediaPermissionController internal constructor(
    private val context: Context,
    private val launch: () -> Unit,
    val canShowRationale: Boolean,
) {
    /**
     * Fires the system permission dialog. On Android 14+ this is also how the user
     * re-opens the photo picker to change which items are shared, because re-requesting
     * `READ_MEDIA_VISUAL_USER_SELECTED` while it is already granted reopens the selection
     * UI rather than being a no-op.
     */
    fun request() = launch()

    /** Last resort when the user has denied twice and the dialog will not appear again. */
    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * @param onResult invoked with the freshly-read state after the system dialog closes.
 */
@Composable
fun rememberMediaPermissionController(
    onResult: (MediaPermissionState) -> Unit = {},
): MediaPermissionController {
    val context = LocalContext.current
    var requestedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // The result map is not trusted directly: on a partial grant the full permissions
        // come back `false` even though the app can now read the selected items, so the
        // real state is always re-read from PackageManager.
        onResult(MediaPermissions.state(context))
    }

    // shouldShowRequestPermissionRationale is false both before the first ask and after a
    // permanent denial, so it is only meaningful once we know we have asked.
    val activity = context.findActivity()
    val canShowRationale = requestedOnce && activity != null &&
        MediaPermissions.requestedPermissions().any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }

    return remember(launcher, canShowRationale) {
        MediaPermissionController(
            context = context,
            launch = {
                requestedOnce = true
                launcher.launch(MediaPermissions.requestedPermissions())
            },
            canShowRationale = canShowRationale,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
