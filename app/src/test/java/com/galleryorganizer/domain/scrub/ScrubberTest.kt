package com.galleryorganizer.domain.scrub

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScrubberTest {

    private fun at(year: Int, month: Int, day: Int = 1): Long =
        LocalDateTime.of(year, month, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun bucket(year: Int, month: Int, count: Int) = MonthBucket(
        year = year,
        month = month,
        itemCount = count,
        latest = at(year, month, 28),
        earliest = at(year, month, 1),
    )

    /** Newest first, the way the grid orders by default. */
    private fun model(vararg buckets: MonthBucket) = ScrubberModel.from(buckets.toList())

    @Test
    fun `offsets accumulate in grid order so a jump lands on that month's first photo`() {
        val m = model(
            bucket(2024, 6, count = 100),
            bucket(2024, 5, count = 50),
            bucket(2024, 4, count = 20),
        )

        assertThat(m.stops.map { it.startOffset }).containsExactly(0, 100, 150).inOrder()
        assertThat(m.total).isEqualTo(170)
    }

    @Test
    fun `rail space is allocated by photo count, not one slot per month`() {
        // The whole point: a month you barely shot must not get the same reach as a month
        // you shot constantly, or the slider is useless in exactly the years you care about.
        val m = model(
            bucket(2024, 6, count = 900),
            bucket(2024, 5, count = 100),
        )

        val busy = m.stops[0]
        val quiet = m.stops[1]

        assertThat(busy.endFraction - busy.startFraction).isWithin(0.001f).of(0.9f)
        assertThat(quiet.endFraction - quiet.startFraction).isWithin(0.001f).of(0.1f)
    }

    @Test
    fun `a point on the rail resolves to the month occupying it`() {
        val m = model(
            bucket(2024, 6, count = 900),
            bucket(2024, 5, count = 100),
        )

        assertThat(m.stopAt(0.0f)?.month).isEqualTo(6)
        assertThat(m.stopAt(0.5f)?.month).isEqualTo(6)
        assertThat(m.stopAt(0.95f)?.month).isEqualTo(5)
    }

    @Test
    fun `dragging to either end lands on the first and last month`() {
        val m = model(
            bucket(2024, 6, count = 10),
            bucket(2023, 1, count = 10),
            bucket(2019, 3, count = 10),
        )

        assertThat(m.stopAt(0f)?.year).isEqualTo(2024)
        assertThat(m.stopAt(1f)?.year).isEqualTo(2019)
        // Past the ends is clamped rather than null — a finger can leave the rail.
        assertThat(m.stopAt(-5f)?.year).isEqualTo(2024)
        assertThat(m.stopAt(9f)?.year).isEqualTo(2019)
    }

    @Test
    fun `the thumb follows the date of what is on screen`() {
        val m = model(
            bucket(2024, 6, count = 100),
            bucket(2024, 5, count = 100),
            bucket(2024, 4, count = 100),
        )

        // Newest month at the top of the rail, oldest at the bottom.
        assertThat(m.fractionForDate(at(2024, 6, 28))).isWithin(0.02f).of(0f)
        assertThat(m.fractionForDate(at(2024, 5, 15))).isWithin(0.1f).of(0.5f)
        assertThat(m.fractionForDate(at(2024, 4, 1))).isWithin(0.02f).of(1f)
    }

    @Test
    fun `the thumb moves through a long month instead of sitting still and jumping`() {
        // One month holding almost everything: if the thumb only moved between months it
        // would be pinned for nine tenths of the library.
        val m = model(
            bucket(2024, 6, count = 9_000),
            bucket(2024, 5, count = 1_000),
        )

        val early = m.fractionForDate(at(2024, 6, 26))
        val late = m.fractionForDate(at(2024, 6, 3))

        assertThat(early).isLessThan(late)
        assertThat(late - early).isGreaterThan(0.3f)
    }

    @Test
    fun `oldest-first inverts the rail so the thumb still follows the grid`() {
        val buckets = listOf(bucket(2019, 1, count = 100), bucket(2024, 6, count = 100))
        val m = ScrubberModel.from(buckets, newestFirst = false)

        // Oldest month is now at the top of the rail.
        assertThat(m.stopAt(0f)?.year).isEqualTo(2019)
        assertThat(m.fractionForDate(at(2019, 1, 1))).isWithin(0.02f).of(0f)
        assertThat(m.fractionForDate(at(2024, 6, 28))).isWithin(0.02f).of(1f)
    }

    @Test
    fun `a date from outside the result still places the thumb somewhere sensible`() {
        // Can happen for a moment after a filter changes: the grid still shows old items
        // while the new histogram has already arrived.
        val m = model(bucket(2024, 6, count = 10), bucket(2024, 5, count = 10))

        val ancient = m.fractionForDate(at(1999, 1, 1))
        val future = m.fractionForDate(at(2030, 1, 1))

        assertThat(ancient).isAtLeast(0f)
        assertThat(ancient).isAtMost(1f)
        assertThat(future).isAtLeast(0f)
        assertThat(future).isAtMost(1f)
    }

    @Test
    fun `year ticks are one per year at the fraction that year starts`() {
        val m = model(
            bucket(2024, 6, count = 100),
            bucket(2024, 1, count = 100),
            bucket(2023, 7, count = 200),
        )

        assertThat(m.yearTicks.map { it.year }).containsExactly(2024, 2023).inOrder()
        assertThat(m.yearTicks.first().fraction).isEqualTo(0f)
        assertThat(m.yearTicks.last().fraction).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `a library with one month offers no slider`() {
        // A slider that cannot go anywhere is worse than no slider: it invites a gesture
        // that does nothing.
        assertThat(model(bucket(2024, 6, count = 5_000)).isUsable).isFalse()
        assertThat(ScrubberModel.Empty.isUsable).isFalse()
        assertThat(model(bucket(2024, 6, count = 10), bucket(2024, 5, count = 10)).isUsable)
            .isTrue()
    }

    @Test
    fun `empty months are dropped rather than taking up rail`() {
        val m = model(
            bucket(2024, 6, count = 100),
            bucket(2024, 5, count = 0),
            bucket(2024, 4, count = 100),
        )

        assertThat(m.stops).hasSize(2)
        assertThat(m.stops.map { it.month }).containsExactly(6, 4).inOrder()
    }

    @Test
    fun `an empty result produces a model that cannot be dragged`() {
        val m = ScrubberModel.from(emptyList())

        assertThat(m.stops).isEmpty()
        assertThat(m.isUsable).isFalse()
        assertThat(m.offsetAt(0.5f)).isEqualTo(0)
        assertThat(m.fractionForDate(at(2024, 6, 1))).isEqualTo(0f)
    }

    @Test
    fun `every point on the rail resolves to a real offset`() {
        val m = model(
            bucket(2024, 6, count = 731),
            bucket(2023, 11, count = 12),
            bucket(2021, 2, count = 4_004),
            bucket(2019, 8, count = 97),
        )

        // Walking the whole rail must never produce an offset outside the result — that
        // would be a jump to nowhere.
        (0..100).forEach { step ->
            val offset = m.offsetAt(step / 100f)
            assertThat(offset).isAtLeast(0)
            assertThat(offset).isLessThan(m.total)
        }
    }

    // --- foldMonths: the part that replaced strftime in SQL ------------------------------

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        java.time.LocalDateTime.of(year, month, day, hour, 0)
            .atZone(java.time.ZoneId.of("Europe/London"))
            .toInstant()
            .toEpochMilli()

    private val london = java.time.ZoneId.of("Europe/London")

    @Test
    fun `folding groups by calendar month and keeps the extremes`() {
        val dates = listOf(
            localMillis(2024, 6, 30),
            localMillis(2024, 6, 1),
            localMillis(2024, 5, 20),
        )

        val buckets = foldMonths(dates.asSequence(), london)

        assertThat(buckets.map { it.month }).containsExactly(6, 5).inOrder()
        assertThat(buckets.first().itemCount).isEqualTo(2)
        assertThat(buckets.first().latest).isEqualTo(localMillis(2024, 6, 30))
        assertThat(buckets.first().earliest).isEqualTo(localMillis(2024, 6, 1))
    }

    @Test
    fun `the first and last instant of a month land in that month, not the next`() {
        // The reason boundaries are computed with java.time rather than by assuming a fixed
        // offset from UTC: an off-by-one-hour boundary would file midnight photos wrongly.
        val lastInstantOfMay = localMillis(2024, 6, 1, hour = 0) - 1
        val firstInstantOfJune = localMillis(2024, 6, 1, hour = 0)

        val buckets = foldMonths(sequenceOf(firstInstantOfJune, lastInstantOfMay), london)

        assertThat(buckets).hasSize(2)
        assertThat(buckets[0].month).isEqualTo(6)
        assertThat(buckets[0].itemCount).isEqualTo(1)
        assertThat(buckets[1].month).isEqualTo(5)
        assertThat(buckets[1].itemCount).isEqualTo(1)
    }

    @Test
    fun `folding survives a daylight saving change inside a month`() {
        // Britain springs forward on the last Sunday of March, so March 2024 is an hour
        // short. Every photo in it must still be filed under March.
        val dates = listOf(
            localMillis(2024, 3, 31, hour = 23),
            localMillis(2024, 3, 30),
            localMillis(2024, 3, 1, hour = 0),
        )

        val buckets = foldMonths(dates.asSequence(), london)

        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().month).isEqualTo(3)
        assertThat(buckets.single().itemCount).isEqualTo(3)
    }

    @Test
    fun `folding an empty library produces nothing rather than an empty month`() {
        assertThat(foldMonths(emptySequence(), london)).isEmpty()
    }

    @Test
    fun `folding works in ascending order too, for oldest-first`() {
        val dates = listOf(
            localMillis(2019, 1, 5),
            localMillis(2019, 2, 5),
            localMillis(2019, 2, 25),
        )

        val buckets = foldMonths(dates.asSequence(), london)

        assertThat(buckets.map { it.month }).containsExactly(1, 2).inOrder()
        assertThat(buckets.last().itemCount).isEqualTo(2)
        assertThat(buckets.last().earliest).isEqualTo(localMillis(2019, 2, 5))
        assertThat(buckets.last().latest).isEqualTo(localMillis(2019, 2, 25))
    }

    @Test
    fun `labels read the way a person would say them`() {
        val stop = model(bucket(2019, 3, count = 10), bucket(2019, 2, count = 10)).stops.first()

        assertThat(stop.fullLabel).isEqualTo("March 2019")
        assertThat(stop.label).isEqualTo("Mar 2019")
    }
}
