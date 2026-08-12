package com.galleryorganizer.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the media library the app can currently see. */
enum class MediaAccess {
    /** Nothing granted. The app cannot index at all. */
    None,

    /**
     * Android 14+ "Select photos and videos". MediaStore returns only the items the
     * user hand-picked. Everything works; it just sees a subset.
     */
    Partial,

    /** At least one of the two full read permissions is granted. */
    Full,
}

/**
 * Permission plumbing for reading the media library.
 *
 * State is always derived from [PackageManager] rather than cached — the user can revoke
 * access from Settings at any moment and a cached copy is one more thing to go stale.
 */
object MediaPermissions {

    const val READ_MEDIA_IMAGES: String = Manifest.permission.READ_MEDIA_IMAGES
    const val READ_MEDIA_VIDEO: String = Manifest.permission.READ_MEDIA_VIDEO

    /**
     * `READ_MEDIA_VISUAL_USER_SELECTED`, spelled out rather than referenced through
     * [Manifest.permission] so the code compiles against any compileSdk and reads the
     * same on every API level.
     */
    const val READ_MEDIA_VISUAL_USER_SELECTED: String =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * Needed to read GPS coordinates out of a photo — MediaStore redacts them otherwise.
     *
     * Requested in the same dialog as the read permissions rather than on its own later:
     * the system will not grant it without a media read grant anyway, and a second prompt
     * days afterwards asking about location is exactly the kind of thing that makes people
     * say no. Refusing it costs nothing but the Places screen.
     */
    const val ACCESS_MEDIA_LOCATION: String = Manifest.permission.ACCESS_MEDIA_LOCATION

    /** True on Android 14 (API 34) and later, where partial media grants exist. */
    val supportsPartialGrant: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * The set to hand to the permission launcher.
     *
     * On Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` must be requested *alongside* the
     * full permissions — that is what makes the system dialog offer "Select photos and
     * videos" at all. Requesting it alone would silently skip the full-access option.
     */
    fun requestedPermissions(
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Array<String> = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            READ_MEDIA_IMAGES,
            READ_MEDIA_VIDEO,
            READ_MEDIA_VISUAL_USER_SELECTED,
            ACCESS_MEDIA_LOCATION,
        )
    } else {
        arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION)
    }

    fun state(context: Context): MediaPermissionState = MediaPermissionState(
        images = context.isGranted(READ_MEDIA_IMAGES),
        video = context.isGranted(READ_MEDIA_VIDEO),
        userSelected = supportsPartialGrant && context.isGranted(READ_MEDIA_VISUAL_USER_SELECTED),
        mediaLocation = context.isGranted(ACCESS_MEDIA_LOCATION),
    )

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * A snapshot of the three media permissions.
 *
 * Kept as a plain data class with no Android types so the interesting logic — how the
 * three booleans collapse into a [MediaAccess] — is testable on the JVM.
 */
data class MediaPermissionState(
    val images: Boolean,
    val video: Boolean,
    val userSelected: Boolean,
    /**
     * Deliberately absent from [access]: without it every photo simply has no location,
     * which is a supported state rather than a broken one.
     */
    val mediaLocation: Boolean = false,
) {
    /**
     * A full grant of *either* images or video outranks a partial grant: once the system
     * has handed over the whole images collection, `READ_MEDIA_VISUAL_USER_SELECTED`
     * adds nothing for that collection.
     */
    val access: MediaAccess = when {
        images || video -> MediaAccess.Full
        userSelected -> MediaAccess.Partial
        else -> MediaAccess.None
    }

    /** True when the indexer can run at all. */
    val canIndex: Boolean get() = access != MediaAccess.None

    /**
     * True when the app can see photos but not videos (or vice versa) because the user
     * granted the two full permissions unevenly. Worth telling them about — it looks
     * like missing files otherwise.
     */
    val isLopsided: Boolean get() = access == MediaAccess.Full && images != video

    companion object {
        val Denied = MediaPermissionState(images = false, video = false, userSelected = false)
    }
}
