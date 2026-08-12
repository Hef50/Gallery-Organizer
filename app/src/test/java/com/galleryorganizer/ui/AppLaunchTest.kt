package com.galleryorganizer.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.galleryorganizer.permissions.MediaPermissions
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Does the app actually start?
 *
 * Embarrassingly, this did not exist until a build shipped that crashed on every launch —
 * two hundred and ninety tests, and not one of them opened the app. The bug was a single
 * unbounded index into Paging's `peek` while the list was still empty, which no amount of
 * testing the pieces in isolation was ever going to catch.
 *
 * This drives the real `MainActivity` through the real `Application`, so the whole first
 * frame is exercised: the container, Room, the permission gate, the paged grid, the date
 * slider, and every `LaunchedEffect` that runs on composition. It is not a substitute for a
 * device, but it is the difference between shipping a crash and not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLaunchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // The gallery schedules indexing on its first composition, and the real WorkManager
        // is not initialised in a Robolectric process.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    /** What the app sees when the user has said yes. */
    private fun grantMediaPermissions() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(*MediaPermissions.requestedPermissions())
    }

    private fun launch() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            // A second pass, so anything scheduled by the first composition also runs.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            controller.get()
        }
    }

    @Test
    fun `the app starts on a device that has granted nothing`() {
        // The permission screen is the first thing a new install shows, so it has to survive
        // being the very first frame.
        launch()
    }

    @Test
    fun `the app starts with media access granted and an empty library`() {
        // The case that shipped broken: permissions granted, so the grid composes — but
        // nothing is indexed yet, so the paged list is empty on the first frame.
        grantMediaPermissions()
        launch()
    }

    @Test
    fun `the app starts with a library already indexed`() {
        grantMediaPermissions()
        val container = com.galleryorganizer.di.AppContainer.from(context)
        kotlinx.coroutines.runBlocking {
            container.database.mediaDao().insertAll(
                (1L..50L).map { id ->
                    com.galleryorganizer.data.db.entity.MediaEntity(
                        mediaStoreId = id,
                        uri = "content://media/external/images/media/$id",
                        displayName = "IMG_$id.jpg",
                        relativePath = "DCIM/Camera/",
                        bucketId = 1,
                        bucketName = "Camera",
                        mime = "image/jpeg",
                        isVideo = false,
                        size = 1_000,
                        dateTaken = 1_700_000_000_000L - id * 86_400_000L,
                        dateModified = 1_700_000_000L,
                        dateAdded = 1_700_000_000L,
                        dateFirstIndexed = 1_700_000_000_000L,
                    )
                },
            )
        }

        launch()
    }
}
