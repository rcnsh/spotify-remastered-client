package app.remastered.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimelineTest {
    private fun line(start: Long, end: Long) = LyricLine(start, end, Vocal(start, end, listOf(Syllable("x", start, end))))

    // 1–2 s, gap, 3–5 s overlapping 4–6 s
    private val lines = listOf(line(1_000, 2_000), line(3_000, 5_000), line(4_000, 6_000))

    @Test fun `before the first line everything is upcoming and the first line is in focus`() {
        assertEquals(List(3) { LineState.Upcoming }, LyricsTimeline.states(lines, 500))
        assertEquals(0, LyricsTimeline.focus(lines, 500))
    }

    @Test fun `a line is active from its start until its end`() {
        assertEquals(LineState.Active, LyricsTimeline.states(lines, 1_000)[0])
        assertEquals(LineState.Past, LyricsTimeline.states(lines, 2_000)[0])
        assertEquals(0, LyricsTimeline.focus(lines, 1_500))
    }

    @Test fun `between lines the last one that started stays in focus`() {
        assertEquals(listOf(LineState.Past, LineState.Upcoming, LineState.Upcoming), LyricsTimeline.states(lines, 2_500))
        assertEquals(0, LyricsTimeline.focus(lines, 2_500))
    }

    @Test fun `overlapping lines are both active and the first has focus`() {
        assertEquals(listOf(LineState.Past, LineState.Active, LineState.Active), LyricsTimeline.states(lines, 4_500))
        assertEquals(1, LyricsTimeline.focus(lines, 4_500))
        assertEquals(2, LyricsTimeline.focus(lines, 5_500))
    }

    @Test fun `after the last line it stays in focus`() {
        assertEquals(List(3) { LineState.Past }, LyricsTimeline.states(lines, 9_000))
        assertEquals(2, LyricsTimeline.focus(lines, 9_000))
    }

    @Test fun `no lines has no focus`() = assertEquals(-1, LyricsTimeline.focus(emptyList(), 0))

    @Test fun `progress runs from 0 to 1 across the span`() {
        assertEquals(0f, LyricsTimeline.progress(1_000, 2_000, 500), 0f)
        assertEquals(.25f, LyricsTimeline.progress(1_000, 2_000, 1_250), 1e-6f)
        assertEquals(1f, LyricsTimeline.progress(1_000, 2_000, 2_500), 0f)
        // Zero-length syllables flip at their start.
        assertEquals(1f, LyricsTimeline.progress(1_000, 1_000, 1_000), 0f)
    }

    @Test fun `an interlude goes before a late first line and in long gaps only`() {
        // Intro 0–3.5 s, a 1 s gap (too short), then a 4 s gap.
        val lines = listOf(line(3_500, 5_000), line(6_000, 7_000), line(11_000, 12_000))
        assertEquals(
            listOf(Interlude(0, 3_500), lines[0], lines[1], Interlude(7_000, 11_000), lines[2]),
            LyricsTimeline.withInterludes(lines),
        )
    }

    @Test fun `overlapping lines count as one stretch of singing`() {
        // The second line ends at 6 s, after the third starts: 6–8 s is a 2 s gap, not 4.
        val lines = listOf(line(1_000, 2_000), line(1_500, 6_000), line(4_000, 5_000), line(8_000, 9_000))
        assertEquals(lines, LyricsTimeline.withInterludes(lines))
    }

    @Test fun `an interlude follows the alignment of the line after it`() {
        val duet = LyricLine(4_000, 5_000, Vocal(4_000, 5_000, listOf(Syllable("x", 4_000, 5_000))), oppositeAligned = true)
        assertEquals(Interlude(0, 4_000, oppositeAligned = true), LyricsTimeline.withInterludes(listOf(duet))[0])
    }

    @Test fun `an interlude is the focus while it plays`() {
        val rows = LyricsTimeline.withInterludes(listOf(line(1_000, 2_000), line(6_000, 7_000)))
        assertEquals(1, LyricsTimeline.focus(rows, 4_000))
        assertEquals(LineState.Active, LyricsTimeline.states(rows, 4_000)[1])
    }

    @Test fun `the dots fill one after another and are full before the interlude ends`() {
        // 0–3.55 s: fills over 0–3 s, a dot per second.
        val gap = Interlude(0, 3_550)
        assertEquals(listOf(0f, 0f, 0f), LyricsTimeline.dots(gap, 0))
        assertEquals(listOf(.5f, 0f, 0f), LyricsTimeline.dots(gap, 500))
        assertEquals(listOf(1f, .5f, 0f), LyricsTimeline.dots(gap, 1_500))
        assertEquals(listOf(1f, 1f, 1f), LyricsTimeline.dots(gap, 3_000))
    }
}
