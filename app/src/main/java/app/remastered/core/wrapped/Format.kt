package app.remastered.core.wrapped

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** The story's wording helpers (web: remotion/format.ts). */
object WrappedFormat {
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** `toLocaleString('en-GB')`: 47598 → "47,598". */
    fun number(n: Int): String = String.format(Locale.UK, "%,d", n)

    fun percent(share: Double): String = "${jsRound(share * 100)}%"

    /** 2025-05 → "May". */
    fun monthName(month: YearMonth): String = MONTHS[month.monthValue - 1]

    /** 2025-07-22 → "22 July" (or "22 July 2025"). */
    fun dayName(day: LocalDate, withYear: Boolean = false): String {
        val label = "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
        return if (withYear) "$label ${day.year}" else label
    }

    data class RangeTitle(val big: String, val small: String?)

    /**
     * What the story calls the range: a bare year for a calendar year, otherwise the two ends.
     * Spotify only ever does years; this does any closed range.
     */
    fun rangeTitle(from: LocalDate, to: LocalDate): RangeTitle {
        val fy = from.year.toString()
        val ty = to.year.toString()
        if (from.monthValue == 1 && from.dayOfMonth == 1 && to.monthValue == 12 && to.dayOfMonth == 31) {
            return RangeTitle(if (fy == ty) fy else "$fy–$ty", null)
        }
        if (fy == ty && from.dayOfMonth == 1 && from.monthValue == to.monthValue && to.dayOfMonth >= 28) {
            return RangeTitle(monthName(YearMonth.from(from)).uppercase(Locale.ROOT), fy)
        }
        val sameYear = fy == ty
        return RangeTitle(if (sameYear) fy else "$fy–$ty", "${dayName(from, !sameYear)} – ${dayName(to, true)}")
    }

    /** The summary card's title (web Summary): a bare year for a calendar year, else both ends with years. */
    fun summaryTitle(from: LocalDate, to: LocalDate): String =
        if (from.monthValue == 1 && from.dayOfMonth == 1 && to.monthValue == 12 && to.dayOfMonth == 31 && from.year == to.year) from.year.toString()
        else "${dayName(from, true)} – ${dayName(to, true)}"

    data class Persona(val title: String, val line: String)

    private val PERSONAS = listOf(
        5 to Persona("The Vampire", "You come alive after midnight."),
        10 to Persona("The Early Bird", "First thing in the morning, headphones in."),
        14 to Persona("The Daydreamer", "Late mornings and long lunches, soundtracked."),
        18 to Persona("The Afternoon Drifter", "The afternoon slump never stood a chance."),
        22 to Persona("The Golden Hour", "Your music hits hardest as the sun goes down."),
        24 to Persona("The Night Owl", "Everyone else was asleep. You were listening."),
    )

    /**
     * A listening personality from the busiest three-hour stretch of the day, in the spirit of the
     * ones Spotify hands out. [hours] has 24 entries.
     */
    fun persona(hours: List<Int>): Pair<Persona, Int> {
        val h24 = List(24) { hours.getOrElse(it) { 0 } }
        var peakHour = 0
        var best = -1
        for (h in 0 until 24) {
            val window = h24[(h + 23) % 24] + h24[h] + h24[(h + 1) % 24]
            if (window > best) {
                best = window
                peakHour = h
            }
        }
        val match = PERSONAS.firstOrNull { (until, _) -> peakHour < until } ?: PERSONAS[0]
        return match.second to peakHour
    }

    fun hourLabel(hour: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        return "$h${if (hour < 12) "am" else "pm"}"
    }
}
