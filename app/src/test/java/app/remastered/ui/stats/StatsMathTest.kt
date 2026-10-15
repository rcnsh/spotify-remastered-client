package app.remastered.ui.stats

import app.remastered.core.warehouse.DayPlays
import app.remastered.core.warehouse.EraRhythm
import app.remastered.core.warehouse.Leader
import app.remastered.core.warehouse.MonthPlays
import app.remastered.core.warehouse.Rhythm
import app.remastered.core.warehouse.RhythmYear
import app.remastered.core.warehouse.StreakSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatsMathTest {
    private val today = LocalDate.of(2026, 10, 13)

    // --- ranges ---

    @Test fun `presets end today and count today`() {
        assertEquals(DayRange(LocalDate.of(2026, 10, 7), today), RangePreset.Week.resolve(today))
        assertEquals(30, RangePreset.Month.resolve(today).days)
        assertEquals(365, RangePreset.Year.resolve(today).days)
        // Across a leap day.
        assertEquals(LocalDate.of(2027, 3, 2), RangePreset.Year.resolve(LocalDate.of(2028, 2, 29)).from)
    }

    @Test fun `custom ranges are put in order, end by today and stay under the warehouse limit`() {
        assertEquals(DayRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)),
            customRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), today))
        assertEquals(today, customRange(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1), today).to)
        assertEquals(DayRange(today, today), customRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1), today))
        val long = customRange(LocalDate.of(2000, 1, 1), today, today)
        assertEquals(3_659, long.days)
        assertEquals(today, long.to)
    }

    // --- buckets ---

    @Test fun `bars are days up to three months, then weeks, then months`() {
        assertEquals(Bucketing.Day, bucketingFor(30))
        assertEquals(Bucketing.Day, bucketingFor(92))
        assertEquals(Bucketing.Week, bucketingFor(365))
        assertEquals(Bucketing.Month, bucketingFor(800))
    }

    private fun days(from: LocalDate, vararg plays: Int) = plays.mapIndexed { i, p -> DayPlays(from.plusDays(i.toLong()), p) }

    @Test fun `days stay as they are`() {
        val d = days(today, 1, 2, 3)
        assertEquals(listOf(1, 2, 3), bucket(d, Bucketing.Day).map { it.plays })
        assertEquals(today, bucket(d, Bucketing.Day).first().end)
    }

    @Test fun `weeks start on Monday and are clipped to the range`() {
        // 2026-10-08 is a Thursday: Thu–Sun, then Mon–Wed.
        val b = bucket(days(LocalDate.of(2026, 10, 8), 1, 1, 1, 1, 2, 2, 2), Bucketing.Week)
        assertEquals(listOf(4, 6), b.map { it.plays })
        assertEquals(LocalDate.of(2026, 10, 8) to LocalDate.of(2026, 10, 11), b[0].start to b[0].end)
        assertEquals(LocalDate.of(2026, 10, 12) to LocalDate.of(2026, 10, 14), b[1].start to b[1].end)
    }

    @Test fun `months split at the first`() {
        val b = bucket(days(LocalDate.of(2026, 1, 30), 5, 5, 1, 1), Bucketing.Month)
        assertEquals(listOf(10, 2), b.map { it.plays })
        assertEquals(LocalDate.of(2026, 1, 31), b[0].end)
        assertEquals(LocalDate.of(2026, 2, 1), b[1].start)
    }

    @Test fun `axis tops are round numbers`() {
        assertEquals(1, niceCeiling(0))
        assertEquals(1, niceCeiling(1))
        assertEquals(5, niceCeiling(3))
        assertEquals(10, niceCeiling(6))
        assertEquals(200, niceCeiling(101))
        assertEquals(500, niceCeiling(500))
        assertEquals(1000, niceCeiling(501))
    }

    // --- months and sparklines ---

    @Test fun `month series fills quiet months with zero`() {
        val s = monthSeries(listOf(MonthPlays(YearMonth.of(2025, 11), 4), MonthPlays(YearMonth.of(2026, 2), 2)), YearMonth.of(2026, 3))
        assertEquals((0..4).map { YearMonth.of(2025, 11).plusMonths(it.toLong()) }, s.map { it.month })
        assertEquals(listOf(4, 0, 0, 2, 0), s.map { it.plays })
    }

    @Test fun `month series can start earlier and is empty without plays`() {
        assertEquals(3, monthSeries(emptyList(), YearMonth.of(2026, 3), from = YearMonth.of(2026, 1)).size)
        assertEquals(emptyList<MonthPlays>(), monthSeries(emptyList(), YearMonth.of(2026, 3)))
    }

    @Test fun `sparkline values scale to the largest`() {
        assertEquals(listOf(0f, .5f, 1f), scaled(listOf(0, 5, 10)))
        assertEquals(listOf(0f, 0f), scaled(listOf(0, 0)))
        assertEquals(emptyList<Float>(), scaled(emptyList()))
    }

    // --- rhythm scopes and eras ---

    private fun era(key: String, hours: Boolean) =
        EraRhythm(key, key, YearMonth.of(2020, 1), null, if (hours) List(24) { 1.0 / 24 } else null, null, null, null)

    private fun streaks(vararg thresholds: Int) = thresholds.map { StreakSet(it, 0, null, emptyList(), .5) }

    private val rhythm = Rhythm(
        null, null, null,
        streaks = streaks(25, 1, 10),
        eras = listOf(era("old", true), era("new", false)),
        years = listOf(
            RhythmYear(2025, false, streaks(1), listOf(era("old", true))),
            RhythmYear(2026, true, streaks(1, 10), listOf(era("old", true), era("new", true))),
        ),
    )

    @Test fun `scopes are all time then years newest first`() {
        assertEquals(listOf(RhythmScope.AllTime, RhythmScope.Year(2026, true), RhythmScope.Year(2025, false)), rhythmScopes(rhythm))
    }

    @Test fun `streaks and eras follow the scope`() {
        assertEquals(listOf(1, 10, 25), streaksIn(rhythm, RhythmScope.AllTime).map { it.threshold })
        assertEquals(listOf(1, 10), streaksIn(rhythm, RhythmScope.Year(2026, true)).map { it.threshold })
        assertEquals(emptyList<StreakSet>(), streaksIn(rhythm, RhythmScope.Year(1999, false)))
        assertEquals(listOf("old"), erasIn(rhythm, RhythmScope.Year(2025, false)).map { it.key })
    }

    @Test fun `the first era shown is the latest with hours`() {
        assertEquals("old", defaultEra(rhythm.eras)?.key)
        assertEquals("new", defaultEra(erasIn(rhythm, RhythmScope.Year(2026, true)))?.key)
        assertEquals("x", defaultEra(listOf(era("x", false)))?.key)
        assertNull(defaultEra(emptyList()))
    }

    @Test fun `month spans read naturally`() {
        assertEquals("Jul 2020 – Jul 2026", monthSpan(YearMonth.of(2020, 7), YearMonth.of(2026, 7)))
        assertEquals("Since Aug 2026", monthSpan(YearMonth.of(2026, 8), null))
        assertEquals("Aug 2026", monthSpan(YearMonth.of(2026, 8), YearMonth.of(2026, 8)))
        assertEquals("", monthSpan(null, null))
    }

    @Test fun `short month spans fit a narrow card`() {
        assertEquals("Aug–Dec 2024", shortMonthSpan(YearMonth.of(2024, 8), YearMonth.of(2024, 12)))
        assertEquals("Nov 24 – Jan 25", shortMonthSpan(YearMonth.of(2024, 11), YearMonth.of(2025, 1)))
        assertEquals("Aug 2026", shortMonthSpan(YearMonth.of(2026, 8), YearMonth.of(2026, 8)))
        assertEquals("Since Aug 2026", shortMonthSpan(YearMonth.of(2026, 8), null))
    }

    // --- top over time ---

    @Test fun `leader shares stack into bands`() {
        val bands = stackShares(listOf(Leader("a", 1, listOf(.2, .5)), Leader("b", 1, listOf(.3, .6))), 2)
        assertEquals(listOf(0f to .2f, 0f to .5f), bands[0])
        assertEquals(.2f, bands[1][0].first, 1e-6f)
        assertEquals(.5f, bands[1][0].second, 1e-6f)
        // Rounding past 100% is capped.
        assertEquals(1f, bands[1][1].second, 1e-6f)
    }

    @Test fun `a leader with fewer shares than quarters stacks zero`() {
        val bands = stackShares(listOf(Leader("a", 1, listOf(.4))), 3)
        assertEquals(listOf(0f to .4f, 0f to 0f, 0f to 0f), bands[0])
    }

    @Test fun `percentages`() {
        assertEquals("0%", percent(0.0))
        assertEquals("<1%", percent(.004))
        assertEquals("4.5%", percent(.045))
        assertEquals("5%", percent(.05))
        assertEquals("63%", percent(.626))
    }
}
