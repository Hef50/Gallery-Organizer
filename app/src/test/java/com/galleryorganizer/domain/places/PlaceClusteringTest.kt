package com.galleryorganizer.domain.places

import com.galleryorganizer.data.db.dao.MediaPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class PlaceClusteringTest {

    private var nextId = 1L
    private fun point(lat: Double, lon: Double) = MediaPoint(nextId++, lat, lon)

    @Test
    fun `photos taken in the same place land in one cluster`() {
        // Twenty photos scattered over a few hundred metres in Kyoto.
        val points = (0 until 20).map { point(35.0116 + it * 0.0002, 135.7681 + it * 0.0002) }

        val clusters = clusterPoints(points, PlaceZoom.City)

        assertThat(clusters).hasSize(1)
        assertThat(clusters.single().count).isEqualTo(20)
    }

    @Test
    fun `photos taken in different cities stay apart`() {
        val points = listOf(
            point(35.0116, 135.7681), // Kyoto
            point(35.6762, 139.6503), // Tokyo
            point(51.5074, -0.1278), // London
        )

        val clusters = clusterPoints(points, PlaceZoom.City)

        assertThat(clusters).hasSize(3)
        assertThat(clusters.map { it.count }).containsExactly(1, 1, 1)
    }

    @Test
    fun `a coarser level merges what a finer one separates`() {
        val points = listOf(
            point(35.0116, 135.7681), // Kyoto
            point(34.6937, 135.5023), // Osaka, ~40 km away
        )

        assertThat(clusterPoints(points, PlaceZoom.City)).hasSize(2)
        assertThat(clusterPoints(points, PlaceZoom.Country)).hasSize(1)
    }

    @Test
    fun `the pin sits on the photos rather than in the middle of the cell`() {
        // Ninety-nine photos in one corner and one straggler at the far side of the cell.
        val points = (0 until 99).map { point(35.00, 135.05) } + point(35.08, 135.15)

        val cluster = clusterPoints(points, PlaceZoom.City).single()

        assertThat(cluster.count).isEqualTo(100)
        // The pin sits on the crowd...
        assertThat(cluster.latitude).isWithin(0.002).of(35.00)
        assertThat(cluster.longitude).isWithin(0.002).of(135.05)
        // ...and not in the middle of the cluster's own extent, which is where a
        // bounding-box centre would put it.
        assertThat(abs(cluster.latitude - (cluster.minLat + cluster.maxLat) / 2))
            .isGreaterThan(0.03)
    }

    @Test
    fun `cluster ids are stable across independent runs and differ per level`() {
        val points = listOf(point(35.0116, 135.7681), point(35.0117, 135.7682))

        val first = clusterPoints(points, PlaceZoom.City).single().id
        val second = clusterPoints(points.reversed(), PlaceZoom.City).single().id
        val coarser = clusterPoints(points, PlaceZoom.Country).single().id

        assertThat(second).isEqualTo(first)
        assertThat(coarser).isNotEqualTo(first)
    }

    @Test
    fun `bounds always cover every point in the cluster`() {
        val points = (0 until 50).map { point(35.02 + it * 0.0001, 135.09 - it * 0.0001) }

        val cluster = clusterPoints(points, PlaceZoom.City).single()

        assertThat(cluster.minLat).isAtMost(points.minOf { it.lat })
        assertThat(cluster.maxLat).isAtLeast(points.maxOf { it.lat })
        assertThat(cluster.minLon).isAtMost(points.minOf { it.lon })
        assertThat(cluster.maxLon).isAtLeast(points.maxOf { it.lon })
    }

    @Test
    fun `a single photo still gets a box with area so it can be tapped`() {
        val cluster = clusterPoints(listOf(point(35.0, 135.0)), PlaceZoom.Spot).single()

        val bounds = cluster.paddedBounds()

        assertThat(bounds.latSpan).isGreaterThan(0.0)
        assertThat(bounds.lonSpan).isGreaterThan(0.0)
        assertThat(bounds.minLat).isLessThan(35.0)
        assertThat(bounds.maxLat).isGreaterThan(35.0)
    }

    @Test
    fun `cells stay roughly square in kilometres far from the equator`() {
        // Two photos 5 km apart in longitude at 70 degrees north. At that latitude a degree
        // of longitude is ~38 km, so a naive square-degree grid would split them; the
        // cos-corrected grid keeps them together.
        val points = listOf(point(70.0, 20.0), point(70.0, 20.13))

        assertThat(clusterPoints(points, PlaceZoom.City)).hasSize(1)
    }

    @Test
    fun `implausible coordinates are dropped rather than dragging a cluster to sea`() {
        val points = listOf(
            point(35.0116, 135.7681),
            MediaPoint(999, 91.0, 0.0),
            MediaPoint(1000, 0.0, 200.0),
        )

        val clusters = clusterPoints(points, PlaceZoom.City)

        assertThat(clusters).hasSize(1)
        assertThat(clusters.single().count).isEqualTo(1)
    }

    @Test
    fun `an empty library produces no clusters and the world as its frame`() {
        assertThat(clusterPoints(emptyList(), PlaceZoom.City)).isEmpty()
        assertThat(Bounds.around(emptyList())).isEqualTo(Bounds.World)
    }

    @Test
    fun `bounds around points frame every one of them`() {
        val points = listOf(point(35.0, 135.0), point(51.5, -0.1), point(-33.9, 151.2))

        val bounds = Bounds.around(points)

        assertThat(bounds.minLat).isEqualTo(-33.9)
        assertThat(bounds.maxLat).isEqualTo(51.5)
        assertThat(bounds.minLon).isEqualTo(-0.1)
        assertThat(bounds.maxLon).isEqualTo(151.2)
    }

    @Test
    fun `expanding a degenerate box still gives it area`() {
        val bounds = Bounds(35.0, 35.0, 135.0, 135.0).expandedBy(0.1)

        assertThat(bounds.latSpan).isGreaterThan(0.0)
        assertThat(bounds.lonSpan).isGreaterThan(0.0)
    }

    @Test
    fun `expanding never escapes the valid coordinate range`() {
        val bounds = Bounds(-89.9, 89.9, -179.9, 179.9).expandedBy(0.5)

        assertThat(bounds.minLat).isAtLeast(-90.0)
        assertThat(bounds.maxLat).isAtMost(90.0)
        assertThat(bounds.minLon).isAtLeast(-180.0)
        assertThat(bounds.maxLon).isAtMost(180.0)
    }

    @Test
    fun `the level chosen for a viewport divides it into a workable number of groups`() {
        // A continent-sized viewport groups by country; a street-sized one by exact spot.
        assertThat(PlaceZoom.forSpan(40.0)).isEqualTo(PlaceZoom.Country)
        assertThat(PlaceZoom.forSpan(0.02)).isEqualTo(PlaceZoom.Spot)
    }

    @Test
    fun `clusters come back biggest first so the map draws the important pins on top`() {
        val points = List(3) { point(35.0, 135.0) } +
            List(9) { point(51.5, -0.1) } +
            List(5) { point(-33.9, 151.2) }

        val counts = clusterPoints(points, PlaceZoom.City).map { it.count }

        assertThat(counts).isInOrder(compareByDescending<Int> { it })
    }
}
