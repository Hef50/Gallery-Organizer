package com.galleryorganizer.domain.places

import com.galleryorganizer.data.db.dao.MediaPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A group of photos taken close together.
 *
 * [id] is stable for a given (level, cell) so that recomposing or re-clustering the same
 * library keeps list keys and selection intact.
 */
data class PlaceCluster(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val coverMediaId: Long,
) {
    /** A box that always has area, so a single-photo cluster is still tappable on the map. */
    fun paddedBounds(minSpanDegrees: Double = 0.0005): Bounds {
        val latPad = max(0.0, (minSpanDegrees - (maxLat - minLat)) / 2)
        val lonPad = max(0.0, (minSpanDegrees - (maxLon - minLon)) / 2)
        return Bounds(minLat - latPad, maxLat + latPad, minLon - lonPad, maxLon + lonPad)
    }
}

data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    val latSpan: Double get() = maxLat - minLat
    val lonSpan: Double get() = maxLon - minLon
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    fun expandedBy(fraction: Double): Bounds {
        val latPad = max(latSpan * fraction, MIN_PAD)
        val lonPad = max(lonSpan * fraction, MIN_PAD)
        return Bounds(
            max(-90.0, minLat - latPad),
            min(90.0, maxLat + latPad),
            max(-180.0, minLon - lonPad),
            min(180.0, maxLon + lonPad),
        )
    }

    companion object {
        private const val MIN_PAD = 0.002

        /** The whole-world box, used when there is nothing to frame. */
        val World = Bounds(-60.0, 75.0, -180.0, 180.0)

        fun around(points: List<MediaPoint>): Bounds {
            if (points.isEmpty()) return World
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (p in points) {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            return Bounds(minLat, maxLat, minLon, maxLon)
        }
    }
}

/**
 * How coarsely to group. The sizes are latitude degrees; one degree of latitude is ~111 km
 * everywhere, so these are honest distances rather than pixels.
 */
enum class PlaceZoom(val label: String, val cellDegrees: Double) {
    Country("Countries", 4.0),
    Region("Regions", 0.8),
    City("Cities", 0.15),
    Neighbourhood("Neighbourhoods", 0.03),
    Spot("Exact spots", 0.004),
    ;

    companion object {
        /** The level whose cells are closest to a tenth of what the viewport is showing. */
        fun forSpan(latSpan: Double): PlaceZoom {
            val target = latSpan / 10
            return entries.minByOrNull { abs(it.cellDegrees - target) } ?: City
        }
    }
}

/**
 * Buckets points into a fixed geographic grid.
 *
 * A grid rather than k-means or DBSCAN for two reasons. It is O(n) with no distance matrix,
 * which matters when "n" is every located photo in a 150k library and this runs on the main
 * thread's budget; and it is *stable* — the same photo lands in the same cell no matter what
 * else is in the library, so panning the map never reshuffles the groups underneath a
 * half-finished gesture. The visible cost is a seam: two photos either side of a cell edge
 * are shown apart even though they were taken together. In practice cells are chosen an
 * order of magnitude smaller than the viewport, so a seam is a couple of pixels.
 *
 * Longitude cell width is divided by cos(latitude) so that cells stay roughly square in
 * kilometres instead of collapsing into slivers near the poles.
 */
fun clusterPoints(points: List<MediaPoint>, zoom: PlaceZoom): List<PlaceCluster> =
    clusterPoints(points, zoom.cellDegrees, zoom.name)

fun clusterPoints(
    points: List<MediaPoint>,
    cellDegrees: Double,
    levelKey: String = cellDegrees.toString(),
): List<PlaceCluster> {
    require(cellDegrees > 0) { "Cell size must be positive" }
    if (points.isEmpty()) return emptyList()

    val cells = LinkedHashMap<Long, Accumulator>()
    for (p in points) {
        if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) continue
        val latIdx = floor(p.lat / cellDegrees).toInt()
        val lonIdx = floor(p.lon / lonCellFor(latIdx, cellDegrees)).toInt()
        // Two ints packed into one long: a cheap composite key with no allocation.
        val key = (latIdx.toLong() shl 32) or (lonIdx.toLong() and 0xFFFF_FFFFL)
        cells.getOrPut(key) { Accumulator(latIdx, lonIdx, p.id) }.add(p)
    }

    return cells.values
        .map { it.toCluster(levelKey) }
        .sortedByDescending { it.count }
}

/**
 * Longitude cell width for a latitude band, widened by 1/cos(lat) at the band's centre.
 * Derived from the band index rather than the point's own latitude so that every point in a
 * band uses the same width — otherwise two neighbouring photos could compute different
 * grids and land in cells that overlap.
 */
private fun lonCellFor(latIdx: Int, cellDegrees: Double): Double {
    val bandCenter = (latIdx + 0.5) * cellDegrees
    val scale = cos(Math.toRadians(bandCenter.coerceIn(-89.5, 89.5)))
    return (cellDegrees / max(scale, 0.02)).coerceAtMost(360.0)
}

private class Accumulator(val latIdx: Int, val lonIdx: Int, val firstId: Long) {
    var count = 0
    var latSum = 0.0
    var lonSum = 0.0
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE

    fun add(p: MediaPoint) {
        count++
        latSum += p.lat
        lonSum += p.lon
        minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
        minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
    }

    fun toCluster(levelKey: String) = PlaceCluster(
        id = "$levelKey:$latIdx:$lonIdx",
        // The centroid, not the cell centre: a hundred photos in one corner of a cell should
        // put the pin on the photos, not in an empty field next to them.
        latitude = latSum / count,
        longitude = lonSum / count,
        count = count,
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon,
        coverMediaId = firstId,
    )
}
