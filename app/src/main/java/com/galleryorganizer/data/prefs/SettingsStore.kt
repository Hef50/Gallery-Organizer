package com.galleryorganizer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Small user preferences. Anything that is really *data* lives in Room; this is only for
 * toggles and one-shot flags.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val hasAskedMediaPermission: Flow<Boolean> =
        store.data.map { it[Keys.HasAskedMediaPermission] ?: false }

    val partialAccessBannerDismissed: Flow<Boolean> =
        store.data.map { it[Keys.PartialAccessBannerDismissed] ?: false }

    /** P8: writing tags into the user's actual files is opt-in. */
    val xmpWriteBackEnabled: Flow<Boolean> =
        store.data.map { it[Keys.XmpWriteBackEnabled] ?: false }

    /** P8: sidecar `.xmp` files never touch the original, so they are a softer toggle. */
    val xmpSidecarEnabled: Flow<Boolean> =
        store.data.map { it[Keys.XmpSidecarEnabled] ?: false }

    /** P9: below this confidence an ML Kit label is not even offered as a suggestion. */
    val autoTagConfidence: Flow<Float> =
        store.data.map { it[Keys.AutoTagConfidence] ?: DEFAULT_AUTO_TAG_CONFIDENCE }

    val autoTagEnabled: Flow<Boolean> =
        store.data.map { it[Keys.AutoTagEnabled] ?: false }

    /** P10: bucket ids the user has chosen to hide from the grid. */
    val hiddenBucketIds: Flow<Set<Long>> =
        store.data.map { prefs ->
            prefs[Keys.HiddenBucketIds].orEmpty().mapNotNull(String::toLongOrNull).toSet()
        }

    val showMissingItems: Flow<Boolean> =
        store.data.map { it[Keys.ShowMissingItems] ?: false }

    suspend fun setHasAskedMediaPermission(value: Boolean) = put(Keys.HasAskedMediaPermission, value)

    suspend fun setPartialAccessBannerDismissed(value: Boolean) =
        put(Keys.PartialAccessBannerDismissed, value)

    suspend fun setXmpWriteBackEnabled(value: Boolean) = put(Keys.XmpWriteBackEnabled, value)

    suspend fun setXmpSidecarEnabled(value: Boolean) = put(Keys.XmpSidecarEnabled, value)

    suspend fun setAutoTagEnabled(value: Boolean) = put(Keys.AutoTagEnabled, value)

    suspend fun setAutoTagConfidence(value: Float) =
        put(Keys.AutoTagConfidence, value.coerceIn(0f, 1f))

    suspend fun setShowMissingItems(value: Boolean) = put(Keys.ShowMissingItems, value)

    suspend fun setBucketHidden(bucketId: Long, hidden: Boolean) {
        store.edit { prefs ->
            val current = prefs[Keys.HiddenBucketIds].orEmpty().toMutableSet()
            if (hidden) current += bucketId.toString() else current -= bucketId.toString()
            prefs[Keys.HiddenBucketIds] = current
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private object Keys {
        val HasAskedMediaPermission = booleanPreferencesKey("has_asked_media_permission")
        val PartialAccessBannerDismissed = booleanPreferencesKey("partial_access_banner_dismissed")
        val XmpWriteBackEnabled = booleanPreferencesKey("xmp_write_back_enabled")
        val XmpSidecarEnabled = booleanPreferencesKey("xmp_sidecar_enabled")
        val AutoTagEnabled = booleanPreferencesKey("auto_tag_enabled")
        val AutoTagConfidence = floatPreferencesKey("auto_tag_confidence")
        val HiddenBucketIds = stringSetPreferencesKey("hidden_bucket_ids")
        val ShowMissingItems = booleanPreferencesKey("show_missing_items")
    }

    companion object {
        const val DEFAULT_AUTO_TAG_CONFIDENCE = 0.70f
    }
}
