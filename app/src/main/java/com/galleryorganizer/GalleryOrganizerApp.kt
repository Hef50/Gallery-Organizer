package com.galleryorganizer

import android.app.Application
import com.galleryorganizer.di.AppContainer

/**
 * Owns the single process-scoped dependency graph. There is no DI framework here on
 * purpose — the graph is small and entirely process-scoped, so a hand-written container
 * is less machinery than Hilt's processing. See DECISIONS.md.
 */
class GalleryOrganizerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
