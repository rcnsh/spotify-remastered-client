package app.remastered.ui.stats

import app.remastered.core.warehouse.DayPlays
import app.remastered.core.warehouse.EraRhythm
import app.remastered.core.warehouse.Leader
import app.remastered.core.warehouse.MonthPlays
import app.remastered.core.warehouse.Rhythm
import app.remastered.core.warehouse.StreakSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Pure helpers behind the Stats and history charts: ranges, buckets, scaling. No Compose here.

/** An inclusive range of local days. */
data class DayRange(val from: LocalDate, val to: LocalDate) {
    init { require(!to.isBefore(from)) { "to before from" } }
    val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
}

/**
 * The Stats page's ranges. The rolling ones end today, today included; the calendar ones are a
 * month or the year so far, or last month whole.
 */
enum class RangePreset(val label: String) {
    Week("7 days"), Month("30 days"), ThisMonth("This month"), LastMonth("Last month"), ThisYear("This year"), Year("12 months");

    fun resolve(today: LocalDate): DayRange = when (this) {
        Week -> DayRange(today.minusDays(6), today)
        Month -> DayRange(today.minusDays(29), today)
        ThisMonth -> DayRange(today.withDayOfMonth(1), today)
        LastMonth -> YearMonth.from(today).minusMonths(1).let { DayRange(it.atDay(1), it.atEndOfMonth()) }
        ThisYear -> DayRange(today.withDayOfYear(1), today)
        Year -> DayRange(today.minusDays(364), today)
    }
}

private val RangeDay = DateTimeFormatter.ofPattern("d MMM")
private val RangeDayYear = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "3 – 17 Oct 2026" style, compact enough for a chip. */
fun rangeText(r: DayRange): String =
    if (r.from.year == r.to.year) "${r.from.format(RangeDay)} – ${r.to.format(RangeDayYear)}" else "${r.from.format(RangeDayYear)} – ${r.to.format(RangeDayYear)}"

/**
 * A playlist name for [what] over [r]: "Top songs · October 2026" for a calendar month (or the
 * month so far), "Top songs · 2026" for a year (or the year so far), the dates otherwise.
 */
fun rangePlaylistName(what: String, r: DayRange, today: LocalDate): String {
    val month = YearMonth.from(r.from)
    val year = r.from.year
    // A range ending today covers a month or year that isn't over yet.
    fun endsWith(end: LocalDate) = r.to == end || (r.to == today && end.isAfter(today))
    val span = when {
        r.from == month.atDay(1) && YearMonth.from(r.to) == month && endsWith(month.atEndOfMonth()) ->
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        r.from.dayOfYear == 1 && r.to.year == year && endsWith(LocalDate.of(year, 12, 31)) -> "$year"
        r.from == r.to -> r.from.format(RangeDayYear)
        else -> rangeText(r)
    }
    return "$what · $span"
}

/**
 * One day under the warehouse's `/api/daily` limit (3,660 local calendar days, counted in its
 * zone), so a range at the edge isn't refused with a 400.
 */
const val MAX_RANGE_DAYS = 3_659

/** A picked range made valid: in order, not past [today], at most [MAX_RANGE_DAYS] (keeping the end). */
fun customRange(from: LocalDate, to: LocalDate, today: LocalDate): DayRange {
    var a = minOf(from, to)
    val b = minOf(maxOf(from, to), today)
    if (a.isAfter(b)) a = b
    if (ChronoUnit.DAYS.between(a, b) >= MAX_RANGE_DAYS) a = b.minusDays(MAX_RANGE_DAYS - 1L)
    return DayRange(a, b)
}

/** How many days one bar covers: days up to three months, weeks up to two years, then months. */
enum class Bucketing { Day, Week, Month }

fun bucketingFor(days: Int): Bucketing = when {
    days <= 92 -> Bucketing.Day
    days <= 731 -> Bucketing.Week
    else -> Bucketing.Month
}

/** One bar: plays from [start] to [end] (inclusive, clipped to the range). */
data class Bucket(val start: LocalDate, val end: LocalDate, val plays: Int)

/** Groups [days] (oldest first, as `/api/daily` returns them) into bars; weeks start on Monday. */
fun bucket(days: List<DayPlays>, by: Bucketing): List<Bucket> {
    if (by == Bucketing.Day) return days.map { Bucket(it.day, it.day, it.plays) }
    fun keyOf(day: LocalDate) =
        if (by == Bucketing.Week) day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else day.withDayOfMonth(1)
    val out = mutableListOf<Bucket>()
    for (d in days) {
        val last = out.lastOrNull()
        if (last != null && keyOf(last.start) == keyOf(d.day)) out[out.lastIndex] = last.copy(end = d.day, plays = last.plays + d.plays)
        else out += Bucket(d.day, d.day, d.plays)
    }
    return out
}

/** A round axis top at or above [max]: 1, 2 or 5 × a power of ten. */
fun niceCeiling(max: Int): Int {
    if (max <= 0) return 1
    var step = 1
    while (true) {
        for (m in intArrayOf(1, 2, 5)) if (step * m >= max) return step * m
        step *= 10
    }
}

/** A short label for a bar: "Mon 12", "12 Oct", "Oct 2026". */
fun bucketLabel(b: Bucket, by: Bucketing, rangeDays: Int): String = when (by) {
    Bucketing.Day -> if (rangeDays <= 7) b.start.format(DateTimeFormatter.ofPattern("EEE d")) else b.start.format(DateTimeFormatter.ofPattern("d MMM"))
    Bucketing.Week -> "Week of " + b.start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    Bucketing.Month -> b.start.format(DateTimeFormatter.ofPattern("MMM yyyy"))
}

/**
 * Plays for every month from [from] (default: the first with plays) to [to], zero where there were
 * none: the warehouse lists only months with plays.
 */
fun monthSeries(months: List<MonthPlays>, to: YearMonth, from: YearMonth? = null): List<MonthPlays> {
    val start = from ?: months.minOfOrNull { it.month } ?: return emptyList()
    if (start.isAfter(to)) return emptyList()
    val byMonth = months.associate { it.month to it.plays }
    return generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }
        .map { MonthPlays(it, byMonth[it] ?: 0) }.toList()
}

/** Each value as a fraction (0–1) of the largest; all zeros when there's nothing. */
fun scaled(values: List<Int>): List<Float> {
    val max = values.maxOrNull() ?: return emptyList()
    if (max <= 0) return values.map { 0f }
    return values.map { it.toFloat() / max }
}

// --- Rhythm ---

/** What the Rhythm section shows: all time or one year. */
sealed interface RhythmScope {
    val label: String
    data object AllTime : RhythmScope { override val label = "All time" }
    data class Year(val year: Int, val partial: Boolean) : RhythmScope { override val label = "$year" }
}

/** All time, then each year newest first. */
fun rhythmScopes(r: Rhythm): List<RhythmScope> =
    listOf(RhythmScope.AllTime) + r.years.sortedByDescending { it.year }.map { RhythmScope.Year(it.year, it.partial) }

fun streaksIn(r: Rhythm, scope: RhythmScope): List<StreakSet> = when (scope) {
    RhythmScope.AllTime -> r.streaks
    is RhythmScope.Year -> r.years.firstOrNull { it.year == scope.year }?.streaks.orEmpty()
}.sortedBy { it.threshold }

fun erasIn(r: Rhythm, scope: RhythmScope): List<EraRhythm> = when (scope) {
    RhythmScope.AllTime -> r.eras
    is RhythmScope.Year -> r.years.firstOrNull { it.year == scope.year }?.eras.orEmpty()
}

/** The era to show first: the latest one with hours to show, else the latest. */
fun defaultEra(eras: List<EraRhythm>): EraRhythm? = eras.lastOrNull { it.hourShare != null } ?: eras.lastOrNull()

/** "Jul 2020 – Jul 2026", or "Since Aug 2026" while it's still going. */
fun monthSpan(from: YearMonth?, to: YearMonth?): String {
    val f = DateTimeFormatter.ofPattern("MMM yyyy")
    return when {
        from == null && to == null -> ""
        to == null -> "Since ${from!!.format(f)}"
        from == null -> "Until ${to.format(f)}"
        from == to -> from.format(f)
        else -> "${from.format(f)} – ${to.format(f)}"
    }
}

/** "Aug–Dec 2024", "Nov 24 – Jan 25", "Aug 2026": fits a narrow card. */
fun shortMonthSpan(from: YearMonth?, to: YearMonth?): String {
    if (from == null || to == null || from == to) return monthSpan(from, to)
    val m = DateTimeFormatter.ofPattern("MMM")
    return if (from.year == to.year) "${from.format(m)}–${to.format(m)} ${to.year}"
    else "${from.format(DateTimeFormatter.ofPattern("MMM yy"))} – ${to.format(DateTimeFormatter.ofPattern("MMM yy"))}"
}

// --- Top over time ---

/**
 * Stacked bands for the leaders' share of each quarter: for each leader (in order) and quarter, the
 * band's bottom and top (0–1). Shares that add past 1 (rounding) are capped.
 */
fun stackShares(leaders: List<Leader>, quarters: Int): List<List<Pair<Float, Float>>> {
    val base = FloatArray(quarters)
    return leaders.map { l ->
        List(quarters) { q ->
            val bottom = base[q]
            val top = (bottom + (l.share.getOrNull(q) ?: 0.0).toFloat().coerceAtLeast(0f)).coerceAtMost(1f)
            base[q] = top
            bottom to top
        }
    }
}

/** "62%", "4.5%", "<1%". */
fun percent(share: Double): String = when {
    share <= 0.0 -> "0%"
    share < 0.01 -> "<1%"
    share < 0.1 -> "%.1f%%".format(share * 100).replace(".0%", "%")
    else -> "${Math.round(share * 100)}%"
}
