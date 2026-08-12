package com.galleryorganizer.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.galleryorganizer.data.db.entity.MediaEntity
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for the database tests. Room runs on Robolectric's SQLite, which means the
 * whole schema — including the FTS4 virtual table, the recursive CTEs and the foreign-key
 * cascades — is exercised on the JVM with no device or emulator. See DECISIONS.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
abstract class DbTest {

    protected lateinit var db: AppDatabase

    protected val media get() = db.mediaDao()
    protected val tags get() = db.tagDao()
    protected val mediaTags get() = db.mediaTagDao()
    protected val fts get() = db.mediaFtsDao()
    protected val indexState get() = db.indexStateDao()
    protected val savedSearches get() = db.savedSearchDao()

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(connection: androidx.sqlite.db.SupportSQLiteDatabase) {
                        connection.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    protected fun sampleMedia(
        mediaStoreId: Long,
        name: String = "IMG_$mediaStoreId.jpg",
        contentHash: String? = null,
        dateTaken: Long = 1_700_000_000_000L + mediaStoreId,
        dateModified: Long = 1_700_000_000L + mediaStoreId,
        bucketId: Long = 1,
        bucketName: String = "Camera",
        isVideo: Boolean = false,
        relativePath: String = "DCIM/Camera/",
        size: Long = 1_000_000 + mediaStoreId,
        isMissing: Boolean = false,
        ocrText: String? = null,
    ) = MediaEntity(
        contentHash = contentHash,
        mediaStoreId = mediaStoreId,
        uri = "content://media/external/images/media/$mediaStoreId",
        displayName = name,
        relativePath = relativePath,
        bucketId = bucketId,
        bucketName = bucketName,
        mime = if (isVideo) "video/mp4" else "image/jpeg",
        isVideo = isVideo,
        size = size,
        dateTaken = dateTaken,
        dateModified = dateModified,
        dateAdded = dateModified,
        dateFirstIndexed = 1_700_000_000_000L,
        isMissing = isMissing,
        ocrText = ocrText,
    )
}
