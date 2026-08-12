package com.galleryorganizer.di

import android.content.Context
import com.galleryorganizer.GalleryOrganizerApp
import com.galleryorganizer.data.prefs.SettingsStore

/**
 * The whole dependency graph. Everything is lazy so that nothing touches disk during
 * [android.app.Application.onCreate] — the cold-start budget is 1.5 s and opening Room
 * eagerly is the classic way to lose it.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as GalleryOrganizerApp).container
    }
}
