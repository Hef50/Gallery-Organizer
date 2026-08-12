package com.galleryorganizer.domain.scrub

import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** One month's worth of matching items, straight from the histogram query. */
data class MonthBucket(
    val year: Int,
    val month: Int,
    val itemCount: Int,
    val latest: Long,
    val earliest: Long,
)

/**
 * A month on the slider, with where it sits both in the result and on the rail.
 *
 * [startOffset] is a position in the *query*, which is what the grid needs in order to jump.
 * [startFraction] is a position on the *rail*, which is what the thumb needs in order to be
 * drawn. They are different mappings, and conflating them is the classic scrubber bug: a
 * month holding nine thousand photos would occupy the same sliver of rail as a month holding
 * four, so the thumb would fly across the screen for a tiny drag.
 */
data class ScrubStop(
    val year: Int,
    val month: Int,
    val itemCount: Int,
    val startOffset: Int,
    val startFraction: Float,
    val endFraction: Float,
    /** Newest and oldest `date_taken` in this month, for placing the thumb within it. */
    val latest: Long,
    val earliest: Long,
) {
    val label: String
        get() = "${monthName(TextStyle.SHORT)} $year"

    val fullLabel: String
        get() = "${monthName(TextStyle.FULL)} $year"

    private fun monthName(style: TextStyle): String =
        YearMonth.of(year, month).month.getDisplayName(style, Locale.getDefault())
}

/**
 * The date slider's model: everything needed to turn a finger position into a place in the
 * library, and back again.
 *
 * Deliberately built on **dates rather than list indices**. The grid pages without
 * placeholders, so an item's index in the list the UI is holding is not its position in the
 * query, and keeping the two reconciled across loads, prepends and invalidations is exactly
 * the bookkeeping that produced the "tapping a photo opens a different photo" bug. A date
 * has no such problem: every photo carries one, so the thumb's position can always be
 * derived from whatever is on screen, and a drop can always be resolved back to an offset.
 */
class ScrubberModel(
    val stops: List<ScrubStop>,
    val total: Int,
    private val newestFirst: Boolean = true,
) {
    /** One month is not a slider, it is a label. */
    val isUsable: Boolean get() = stops.size >= MIN_STOPS && total > 0

    /** The years to label down the rail, each at the fraction where that year begins. */
    val yearTicks: List<YearTick> by lazy {
        stops.groupBy { it.year }
            .map { (year, months) -> YearTick(year, months.minOf { it.startFraction }) }
            .sortedBy { it.fraction }
    }

    /** Which month a point on the rail lands in. */
    fun stopAt(fraction: Float): ScrubStop? {
        if (stops.isEmpty()) return null
        val f = fraction.coerceIn(0f, 1f)
        return stops.firstOrNull { f >= it.startFraction && f < it.endFraction } ?: stops.last()
    }

    /** Where in the result to jump for a point on the rail. */
    fun offsetAt(fraction: Float): Int = stopAt(fraction)?.startOffset ?: 0

    /**
     * Where the thumb belongs, given the date of the topmost visible photo.
     *
     * Interpolated *within* the month rather than snapped to its start, so the thumb creeps
     * smoothly through a long month instead of sitting still and then jumping. For a library
     * with ten thousand photos from one summer that is most of whether the slider feels
     * attached to the grid or merely adjacent to it.
     */
    fun fractionForDate(dateTaken: Long): Float {
        val stop = stops.firstOrNull { dateTaken in it.earliest..it.latest }
            ?: nearestStop(dateTaken)
            ?: return 0f

        val span = stop.latest - stop.earliest
        val within = when {
            span <= 0L -> 0f
            // Newest first: the newest photo of a month sits at the top of its band.
            newestFirst -> (stop.latest - dateTaken).toFloat() / span
            else -> (dateTaken - stop.earliest).toFloat() / span
        }.coerceIn(0f, 1f)

        return (stop.startFraction + within * (stop.endFraction - stop.startFraction))
            .coerceIn(0f, 1f)
    }

    private fun nearestStop(dateTaken: Long): ScrubStop? = stops.minByOrNull {
        minOf(abs(it.latest - dateTaken), abs(it.earliest - dateTaken))
    }

    companion object {
        const val MIN_STOPS = 2

        val Empty = ScrubberModel(emptyList(), 0)

        /**
         * Builds the model from the histogram.
         *
         * Rail space is allocated by **item count**, not one slot per month, so the rail is
         * a picture of where the photos actually are: a year you barely shot gets a sliver,
         * and the summer you took nine thousand pictures gets a band wide enough to aim at.
         */
        fun from(buckets: List<MonthBucket>, newestFirst: Boolean = true): ScrubberModel {
            val usable = buckets.filter { it.itemCount > 0 }
            if (usable.isEmpty()) return Empty

            val total = usable.sumOf { it.itemCount }
            var offset = 0
            val stops = usable.map { bucket ->
                val start = offset
                offset += bucket.itemCount
                ScrubStop(
                    year = bucket.year,
                    month = bucket.month,
                    itemCount = bucket.itemCount,
                    startOffset = start,
                    startFraction = start.toFloat() / total,
                    endFraction = offset.toFloat() / total,
                    latest = bucket.latest,
                    earliest = bucket.earliest,
                )
            }
            return ScrubberModel(stops, total, newestFirst)
        }
    }
}

data class YearTick(val year: Int, val fraction: Float)

/**
 * Folds a date-ordered sequence of `date_taken` values into month buckets.
 *
 * Doing this in Kotlin rather than with SQL's `strftime(..., 'localtime')` is worth a great
 * deal: `strftime` runs per row and consults the time zone database each time, which
 * measured at over half a second for 150,000 rows on a desktop — seconds on a phone, for
 * something that reruns whenever the filter changes. Because the input is already ordered,
 * a month boundary only has to be computed when one is crossed: roughly a hundred and fifty
 * times for a decade of photographs rather than a hundred and fifty thousand.
 *
 * The boundaries themselves still come from `java.time`, so daylight saving is handled
 * exactly rather than by assuming a fixed offset.
 */
fun foldMonths(dates: Sequence<Long>, zone: java.time.ZoneId): List<MonthBucket> {
    val out = ArrayList<MonthBucket>()
    var year = 0
    var month = 0
    var start = Long.MAX_VALUE
    var endExclusive = Long.MIN_VALUE
    var count = 0
    var latest = Long.MIN_VALUE
    var earliest = Long.MAX_VALUE

    fun flush() {
        if (count > 0) out += MonthBucket(year, month, count, latest, earliest)
    }

    for (date in dates) {
        if (date < start || date >= endExclusive) {
            flush()
            val ym = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(date).atZone(zone))
            year = ym.year
            month = ym.monthValue
            start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            endExclusive = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            count = 0
            latest = Long.MIN_VALUE
            earliest = Long.MAX_VALUE
        }
        count++
        if (date > latest) latest = date
        if (date < earliest) earliest = date
    }
    flush()
    return out
}
