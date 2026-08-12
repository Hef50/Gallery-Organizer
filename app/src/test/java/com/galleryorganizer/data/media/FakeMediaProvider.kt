package com.galleryorganizer.data.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore

/**
 * A stand-in for the system media provider, registered on the `media` authority so the
 * real [ContentResolverMediaStoreSource] can be exercised end to end on the JVM.
 *
 * It implements just enough of MediaStore to be meaningful: the columns the app projects,
 * the `(date_modified, _id)` window the scanner selects on, and that ordering.
 */
class FakeMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val isVideo = uri.pathSegments.contains("video")
        var rows = items.filter { it.isVideo == isVideo }

        // The scanner's window: everything strictly after (dateModified, id).
        if (selection != null && selectionArgs != null && selectionArgs.size == 3) {
            val afterModified = selectionArgs[0].toLong()
            val afterId = selectionArgs[2].toLong()
            rows = rows.filter {
                it.dateModified > afterModified ||
                    (it.dateModified == afterModified && it.mediaStoreId > afterId)
            }
        }
        rows = rows.sortedWith(compareBy({ it.dateModified }, { it.mediaStoreId }))

        val columns = projection ?: ALL_COLUMNS
        val cursor = MatrixCursor(columns)
        for (row in rows) {
            cursor.addRow(columns.map { row.valueOf(it) })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** A row as the fake provider stores it. `dateTaken = null` mimics a non-camera file. */
    data class Row(
        val mediaStoreId: Long,
        val displayName: String = "IMG_$mediaStoreId.jpg",
        val relativePath: String = "DCIM/Camera/",
        val bucketId: Long = 1,
        val bucketName: String = "Camera",
        val mime: String = "image/jpeg",
        val isVideo: Boolean = false,
        val size: Long = 1_000,
        val dateTaken: Long? = 1_700_000_000_000L,
        val dateModified: Long = 1_700_000_000L,
        val dateAdded: Long = 1_700_000_000L,
        val duration: Long = 0,
        val width: Int = 4032,
        val height: Int = 3024,
        val orientation: Int = 0,
    ) {
        fun valueOf(column: String): Any? = when (column) {
            MediaStore.MediaColumns._ID -> mediaStoreId
            MediaStore.MediaColumns.DISPLAY_NAME -> displayName
            MediaStore.MediaColumns.RELATIVE_PATH -> relativePath
            MediaStore.MediaColumns.BUCKET_ID -> bucketId
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME -> bucketName
            MediaStore.MediaColumns.MIME_TYPE -> mime
            MediaStore.MediaColumns.SIZE -> size
            MediaStore.MediaColumns.DATE_TAKEN -> dateTaken
            MediaStore.MediaColumns.DATE_MODIFIED -> dateModified
            MediaStore.MediaColumns.DATE_ADDED -> dateAdded
            MediaStore.MediaColumns.DURATION -> duration
            MediaStore.MediaColumns.WIDTH -> width
            MediaStore.MediaColumns.HEIGHT -> height
            MediaStore.MediaColumns.ORIENTATION -> orientation
            else -> null
        }
    }

    companion object {
        /** Set by the test before querying. Static because the provider is instantiated by Robolectric. */
        var items: List<Row> = emptyList()

        private val ALL_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
        )
    }
}
