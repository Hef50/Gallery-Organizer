package com.galleryorganizer.data.repo

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.dao.MediaPoint
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.media.LocationExtractor
import com.galleryorganizer.domain.places.Bounds
import com.galleryorganizer.domain.places.PlaceCluster
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where photos were taken.
 *
 * Everything here is derived from EXIF on the device. There is deliberately no reverse
 * geocoding — turning 35.0116, 135.7681 into "Kyoto" needs a network service, and this app
 * has no `INTERNET` permission. A cluster is named by the person who was there, which is
 * both offline and more accurate than any gazetteer: see `nameCluster`.
 */
class LocationRepository(
    private val db: AppDatabase,
    private val resolver: ContentResolver,
    private val tags: TagRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mediaDao get() = db.mediaDao()

    suspend fun remaining(): Int = mediaDao.unlocatedCount()

    fun observeLocatedCount(): Flow<Int> = mediaDao.observeLocatedCount()

    /**
     * Reads EXIF coordinates for up to [limit] items and records the outcome for every one
     * of them.
     *
     * "No GPS tag" is written as `location_state = READ` with null coordinates, not left
     * pending. Most photos in a real library have no location at all, and if a miss stayed
     * pending the worker would re-open the same tens of thousands of files on every run
     * forever.
     *
     * @return how many items were examined, so the worker can loop until it returns 0.
     */
    suspend fun readNextBatch(limit: Int): Int = withContext(io) {
        val targets = mediaDao.unlocatedTargets(limit)
        if (targets.isEmpty()) return@withContext 0

        val results = targets.map { target ->
            val location = LocationExtractor.read(resolver, Uri.parse(target.uri))
            Triple(target.id, location, MediaEntity.LOCATION_READ)
        }
        db.withTransaction {
            results.forEach { (id, location, state) ->
                mediaDao.setLocation(id, location?.latitude, location?.longitude, state)
            }
        }
        results.size
    }

    /** Every located photo as a bare coordinate, for the map. */
    suspend fun points(): List<MediaPoint> = withContext(io) { mediaDao.locatedPoints() }

    /** The photos inside a cluster, newest first. */
    suspend fun itemsIn(cluster: PlaceCluster, limit: Int = ITEMS_LIMIT): List<MediaEntity> =
        itemsIn(cluster.paddedBounds(), limit)

    suspend fun itemsIn(bounds: Bounds, limit: Int = ITEMS_LIMIT): List<MediaEntity> =
        withContext(io) {
            mediaDao.inBoundingBox(
                bounds.minLat,
                bounds.maxLat,
                bounds.minLon,
                bounds.maxLon,
                limit,
            )
        }

    /**
     * Names a place, which is the whole point of the map.
     *
     * Without a network there is no way to look up what a coordinate is called, so instead
     * the map is a way of *finding* the photos from one place and the user supplies the
     * name once. That creates (or reuses) a [com.galleryorganizer.data.db.entity.TagKind.Place]
     * tag and applies it to everything in the cluster, in one transaction — so from then on
     * the place is searchable, nestable under a parent like `Japan`, and survives backup and
     * restore like any other tag. A coordinate on its own would do none of that.
     *
     * @return the number of photos newly tagged.
     */
    suspend fun nameCluster(cluster: PlaceCluster, name: String, parentId: Long): Int {
        val ids = itemsIn(cluster, limit = NAME_LIMIT).map { it.id }
        if (ids.isEmpty()) return 0
        val tagId = tags.ensureTag(name, parentId, com.galleryorganizer.data.db.entity.TagKind.Place)
        return tags.applyTags(ids, listOf(tagId))
    }

    private companion object {
        const val ITEMS_LIMIT = 500

        /**
         * Naming is a bulk tag write, and one gesture is one transaction. 20k rows is a
         * fraction of a second and far beyond any real cluster; the cap exists so that a
         * pathological "all my photos are at home" cluster cannot lock the database for
         * long enough to be noticed.
         */
        const val NAME_LIMIT = 20_000
    }
}
