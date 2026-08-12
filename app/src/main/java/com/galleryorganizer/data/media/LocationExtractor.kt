package com.galleryorganizer.data.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** A coordinate read from a photo's EXIF. */
data class MediaLocation(val latitude: Double, val longitude: Double) {
    /** Rejects the null island and anything outside the valid range. */
    val isPlausible: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}

/**
 * Reads GPS coordinates out of a photo.
 *
 * The subtlety is that **MediaStore lies about location by default**. Since Android 10 it
 * strips GPS tags from the file it hands an app, so reading EXIF from the ordinary content
 * URI returns nothing at all no matter what the file contains. The original is only
 * available via [MediaStore.setRequireOriginal], and only when `ACCESS_MEDIA_LOCATION` has
 * been granted — which is why this is a separate pass rather than part of the indexer.
 */
object LocationExtractor {

    fun read(resolver: ContentResolver, uri: Uri): MediaLocation? = try {
        val original = MediaStore.setRequireOriginal(uri)
        resolver.openInputStream(original)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLong = FloatArray(2)
            @Suppress("DEPRECATION")
            if (exif.getLatLong(latLong)) {
                MediaLocation(latLong[0].toDouble(), latLong[1].toDouble())
                    .takeIf { it.isPlausible }
            } else {
                null
            }
        }
    } catch (e: SecurityException) {
        // The permission is missing or was revoked. Not an error: the app works fine
        // without ever knowing where anything was taken.
        null
    } catch (e: Exception) {
        null
    }
}
