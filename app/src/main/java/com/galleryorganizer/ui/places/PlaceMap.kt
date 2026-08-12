package com.galleryorganizer.ui.places

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galleryorganizer.domain.places.Bounds
import com.galleryorganizer.domain.places.PlaceCluster
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The map.
 *
 * There is no basemap, and that is a constraint rather than an omission: map tiles come
 * from a tile server, a tile server is the network, and this app has no `INTERNET`
 * permission for the whole of v1 (see CLAUDE.md). Bundling an offline basemap would mean
 * either a vector planet — hundreds of megabytes — or a coastline outline coarse enough to
 * be decorative rather than informative.
 *
 * So this draws what it actually knows: every located photo as a faint point, the clusters
 * as weighted discs on top, and a graticule with a scale bar for orientation. It reads as
 * a plot of *your* travel rather than a world map, which is the honest thing for it to be
 * — and for the job the screen exists to do, finding the photos from one trip and naming
 * that place, a basemap would not add anything the clusters do not already say.
 *
 * The projection is equirectangular with a cos(latitude) correction on longitude, so shapes
 * near the poles are not stretched sideways.
 */
@Composable
fun PlaceMap(
    frame: Bounds,
    points: List<com.galleryorganizer.data.db.dao.MediaPoint>,
    clusters: List<PlaceCluster>,
    selectedId: String?,
    onSelect: (PlaceCluster?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val gridColor = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
    val pointColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val onAccent = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    val labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant

    // Recomputed only when the frame changes, not on every tap.
    val projector = remember(frame) { Projector(frame) }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(clusters, projector) {
                detectTapGestures { tap ->
                    val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    val slop = TAP_SLOP_DP.dp.toPx()
                    val hit = clusters
                        .map { it to projector.project(it.latitude, it.longitude, size) }
                        .filter { (cluster, at) ->
                            (tap - at).getDistance() <= radiusFor(cluster.count).dp.toPx() + slop
                        }
                        // The smallest disc wins a stacked tap: a big cluster behind a small
                        // one would otherwise make the small one unreachable.
                        .minByOrNull { (cluster, _) -> cluster.count }
                        ?.first
                    onSelect(hit)
                }
            },
    ) {
        drawGraticule(frame, projector, gridColor, measurer, labelColor)

        // Every located photo, faintly. At a glance this is the shape of where someone has
        // been — the clusters say where they lingered.
        points.forEach { p ->
            drawCircle(
                color = pointColor.copy(alpha = 0.22f),
                radius = 1.4.dp.toPx(),
                center = projector.project(p.lat, p.lon, size),
            )
        }

        clusters.forEach { cluster ->
            val center = projector.project(cluster.latitude, cluster.longitude, size)
            val radius = radiusFor(cluster.count).dp.toPx()
            val isSelected = cluster.id == selectedId

            drawCircle(accent.copy(alpha = 0.16f), radius * 1.9f, center)
            drawCircle(accent.copy(alpha = if (isSelected) 0.95f else 0.72f), radius, center)
            if (isSelected) {
                drawCircle(accent, radius * 1.45f, center, style = Stroke(width = 2.dp.toPx()))
            }

            val label = compactCount(cluster.count)
            val measured = measurer.measure(
                label,
                TextStyle(fontSize = 10.sp, color = onAccent),
            )
            drawText(
                measured,
                topLeft = center - Offset(
                    measured.size.width / 2f,
                    measured.size.height / 2f,
                ),
            )
        }

        drawScaleBar(frame, projector, labelColor, measurer)
    }
}

/** Disc radius in dp, by count. Square-rooted so area, not radius, tracks the count. */
private fun radiusFor(count: Int): Float =
    (9f + sqrt(count.toFloat()) * 1.5f).coerceAtMost(34f)

private fun compactCount(count: Int): String = when {
    count >= 10_000 -> "%dk".format(count / 1000)
    count >= 1_000 -> "%.1fk".format(count / 1000f)
    else -> count.toString()
}

/**
 * Maps a coordinate into the canvas.
 *
 * Longitude is scaled by cos(the frame's centre latitude) so that a degree of longitude
 * occupies its true fraction of a degree of latitude's width. Without it a map of Norway
 * looks twice as wide as it is.
 */
private class Projector(private val frame: Bounds) {
    private val lonScale = kotlin.math.cos(Math.toRadians(frame.centerLat.coerceIn(-85.0, 85.0)))
        .coerceAtLeast(0.05)

    private val worldWidth = max(frame.lonSpan * lonScale, 1e-6)
    private val worldHeight = max(frame.latSpan, 1e-6)

    fun project(lat: Double, lon: Double, size: Size): Offset {
        // Fit the frame inside the canvas without distorting it: an aspect-stretched map is
        // a lie about distance, and this one is already only telling you about shape.
        val scale = min(size.width / worldWidth, size.height / worldHeight)
        val drawnWidth = worldWidth * scale
        val drawnHeight = worldHeight * scale
        val offsetX = (size.width - drawnWidth) / 2
        val offsetY = (size.height - drawnHeight) / 2

        val x = offsetX + (lon - frame.minLon) * lonScale * scale
        // Screen y grows downward; latitude grows upward.
        val y = offsetY + (frame.maxLat - lat) * scale
        return Offset(x.toFloat(), y.toFloat())
    }

    fun metresPerPixel(size: Size): Double {
        val scale = min(size.width / worldWidth, size.height / worldHeight)
        return METRES_PER_DEGREE_LAT / scale
    }

    companion object {
        const val METRES_PER_DEGREE_LAT = 111_320.0
    }
}

/**
 * A graticule at a round interval, chosen so there are roughly four lines across the frame.
 * It is the only thing standing in for a basemap, so it has to say something true: the
 * labels are the actual coordinates.
 */
private fun DrawScope.drawGraticule(
    frame: Bounds,
    projector: Projector,
    color: Color,
    measurer: TextMeasurer,
    labelColor: Color,
) {
    val step = niceStep(frame.latSpan / 4)
    val faint = color.copy(alpha = 0.5f)

    var lat = kotlin.math.floor(frame.minLat / step) * step
    while (lat <= frame.maxLat) {
        val y = projector.project(lat, frame.minLon, size).y
        if (y in 0f..size.height) {
            drawLine(faint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            val text = measurer.measure(
                formatDegrees(lat, northSouth = true),
                TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.75f)),
            )
            drawText(text, topLeft = Offset(6.dp.toPx(), y + 2.dp.toPx()))
        }
        lat += step
    }

    var lon = kotlin.math.floor(frame.minLon / step) * step
    while (lon <= frame.maxLon) {
        val x = projector.project(frame.minLat, lon, size).x
        if (x in 0f..size.width) {
            drawLine(faint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        lon += step
    }
}

/** A scale bar, because a map with no basemap needs to say how big it is. */
private fun DrawScope.drawScaleBar(
    frame: Bounds,
    projector: Projector,
    color: Color,
    measurer: TextMeasurer,
) {
    val metresPerPixel = projector.metresPerPixel(size)
    if (!metresPerPixel.isFinite() || metresPerPixel <= 0) return

    val targetPx = size.width * 0.28f
    val metres = niceStep(targetPx * metresPerPixel)
    val barPx = (metres / metresPerPixel).toFloat()
    if (barPx <= 0f || barPx > size.width) return

    val y = size.height - 22.dp.toPx()
    val x = 16.dp.toPx()
    drawLine(color.copy(alpha = 0.7f), Offset(x, y), Offset(x + barPx, y), strokeWidth = 2f)
    drawLine(color.copy(alpha = 0.7f), Offset(x, y - 4.dp.toPx()), Offset(x, y + 4.dp.toPx()), strokeWidth = 2f)
    drawLine(
        color.copy(alpha = 0.7f),
        Offset(x + barPx, y - 4.dp.toPx()),
        Offset(x + barPx, y + 4.dp.toPx()),
        strokeWidth = 2f,
    )

    val label = if (metres >= 1000) "%,d km".format((metres / 1000).roundToInt()) else "%d m".format(metres.roundToInt())
    val text = measurer.measure(label, TextStyle(fontSize = 9.sp, color = color))
    drawText(text, topLeft = Offset(x, y - 16.dp.toPx()))
}

/** 1, 2 or 5 times a power of ten — the intervals people read without thinking. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0 || !raw.isFinite()) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(raw)))
    val normalised = raw / magnitude
    val step = when {
        normalised < 1.5 -> 1.0
        normalised < 3.5 -> 2.0
        normalised < 7.5 -> 5.0
        else -> 10.0
    }
    return step * magnitude
}

private fun formatDegrees(value: Double, northSouth: Boolean): String {
    val suffix = when {
        northSouth && value >= 0 -> "N"
        northSouth -> "S"
        value >= 0 -> "E"
        else -> "W"
    }
    val magnitude = abs(value)
    val decimals = if (magnitude < 1) 3 else if (magnitude < 10) 2 else 1
    return "%.${decimals}f°%s".format(magnitude, suffix)
}

/** Extra reach around a disc, so a small pin is still hittable with a thumb. */
private const val TAP_SLOP_DP = 8f
