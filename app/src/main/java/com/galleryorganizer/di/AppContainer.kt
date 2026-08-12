package com.galleryorganizer.di

import android.content.Context
import com.galleryorganizer.GalleryOrganizerApp
import com.galleryorganizer.data.backup.BackupRepository
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.media.ContentResolverMediaStoreSource
import com.galleryorganizer.data.media.MediaStoreSource
import com.galleryorganizer.data.prefs.SettingsStore
import com.galleryorganizer.data.repo.FtsMaintenance
import com.galleryorganizer.data.repo.MediaIndexer
import com.galleryorganizer.data.repo.MediaRepository
import com.galleryorganizer.data.repo.SearchRepository
import com.galleryorganizer.data.repo.TagRepository

/**
 * The whole dependency graph. Everything is lazy so that nothing touches disk during
 * [android.app.Application.onCreate] — the cold-start budget is 1.5 s and opening Room
 * eagerly is the classic way to lose it.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val ftsMaintenance: FtsMaintenance by lazy { FtsMaintenance(database) }

    val mediaStoreSource: MediaStoreSource by lazy {
        ContentResolverMediaStoreSource(appContext.contentResolver)
    }

    val mediaRepository: MediaRepository by lazy {
        MediaRepository(database, appContext.contentResolver)
    }

    val tagRepository: TagRepository by lazy { TagRepository(database, ftsMaintenance) }

    val searchRepository: SearchRepository by lazy { SearchRepository(database) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(database, tagRepository, ftsMaintenance)
    }

    val mediaIndexer: MediaIndexer by lazy {
        MediaIndexer(mediaStoreSource, database, ftsMaintenance)
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as GalleryOrganizerApp).container
    }
}
