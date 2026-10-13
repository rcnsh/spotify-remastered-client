package app.remastered.core.wrapped

import app.remastered.core.wrapped.WrappedFormat.RangeTitle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Expected values from the web app's format.ts (run under node). */
class FormatTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `numbers and percents read as en-GB with JS rounding`() {
        assertEquals("1,234,567", WrappedFormat.number(1_234_567))
        assertEquals("705", WrappedFormat.number(705))
        assertEquals("9%", WrappedFormat.percent(0.085))
        assertEquals("5%", WrappedFormat.percent(0.045))
        assertEquals("45%", WrappedFormat.percent(0.45))
    }

    @Test fun `range titles`() {
        assertEquals(RangeTitle("MARCH", "2025"), WrappedFormat.rangeTitle(d("2025-03-01"), d("2025-03-31")))
        assertEquals(RangeTitle("2024–2025", "1 July 2024 – 30 June 2025"), WrappedFormat.rangeTitle(d("2024-07-01"), d("2025-06-30")))
        assertEquals(RangeTitle("2025", "1 January – 5 October 2025"), WrappedFormat.rangeTitle(d("2025-01-01"), d("2025-10-05")))
        assertEquals(RangeTitle("2020–2025", null), WrappedFormat.rangeTitle(d("2020-01-01"), d("2025-12-31")))
        assertEquals(RangeTitle("2025", null), WrappedFormat.rangeTitle(d("2025-01-01"), d("2025-12-31")))
        assertEquals("2025", WrappedFormat.summaryTitle(d("2025-01-01"), d("2025-12-31")))
        assertEquals("1 January 2025 – 5 October 2025", WrappedFormat.summaryTitle(d("2025-01-01"), d("2025-10-05")))
    }

    @Test fun `days and months`() {
        assertEquals("22 July", WrappedFormat.dayName(d("2025-07-22")))
        assertEquals("17 April 2025", WrappedFormat.dayName(d("2025-04-17"), withYear = true))
        assertEquals("June", WrappedFormat.monthName(YearMonth.of(2025, 6)))
    }

    @Test fun `persona from the busiest three hours`() {
        val hours = listOf(300, 220, 140, 90, 40, 10, 5, 8, 60, 180, 260, 420, 520, 610, 700, 820, 760, 640, 560, 600, 540, 480, 410, 350)
        val (who, peak) = WrappedFormat.persona(hours)
        assertEquals("The Afternoon Drifter", who.title)
        assertEquals(15, peak)
        assertEquals("The Vampire", WrappedFormat.persona(List(24) { if (it == 2) 10 else 0 }).first.title)
        // The first window to reach the peak wins: 23:00 alone peaks at midnight (it wraps).
        assertEquals("The Vampire", WrappedFormat.persona(List(24) { if (it == 23) 10 else 0 }).first.title)
        assertEquals("The Night Owl", WrappedFormat.persona(List(24) { if (it == 22 || it == 23) 10 else 0 }).first.title)
        assertEquals("3pm", WrappedFormat.hourLabel(15))
        assertEquals("12am", WrappedFormat.hourLabel(0))
        assertEquals("12pm", WrappedFormat.hourLabel(12))
    }
}
