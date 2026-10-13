package app.remastered.core.lyrics

enum class LineState { Upcoming, Active, Past }

/** Where playback is within synced lyrics. Pure functions of the lines and a time in ms. */
object LyricsTimeline {
    /** Each line's state at [t]. Lines can overlap, so more than one may be [LineState.Active]. */
    fun states(lines: List<LyricRow>, t: Long): List<LineState> = lines.map {
        when {
            t < it.startMs -> LineState.Upcoming
            t < it.endMs -> LineState.Active
            else -> LineState.Past
        }
    }

    /**
     * The line to keep in view at [t]: the first one being sung; between lines, the last one that
     * started; before the first line, the first line.
     */
    fun focus(lines: List<LyricRow>, t: Long): Int {
        if (lines.isEmpty()) return -1
        lines.indexOfFirst { t >= it.startMs && t < it.endMs }.let { if (it >= 0) return it }
        return lines.indexOfLast { it.startMs <= t }.coerceAtLeast(0)
    }

    /** Gaps at least this long get an [Interlude], as in the Spicy Lyrics extension. */
    const val InterludeMinMs = 3_000L

    /** The dots are full and go away this long before the next line starts. */
    const val InterludeHideMs = 500L

    /**
     * [lines] with an [Interlude] before the first line if it starts [InterludeMinMs] or more into
     * the song, and in every gap at least that long. Overlapping lines count as one stretch of singing.
     */
    fun withInterludes(lines: List<LyricLine>): List<LyricRow> = buildList {
        var sungUntil = 0L
        lines.forEach { line ->
            if (line.startMs - sungUntil >= InterludeMinMs) add(Interlude(sungUntil, line.startMs, line.oppositeAligned))
            add(line)
            sungUntil = maxOf(sungUntil, line.endMs)
        }
    }

    /**
     * How lit each of [interlude]'s three dots is at [t], from 0 to 1. They fill one after another
     * and are all full a little before [InterludeHideMs], so the last one is seen full.
     */
    fun dots(interlude: Interlude, t: Long): List<Float> {
        val fillEnd = maxOf(interlude.startMs, interlude.endMs - InterludeHideMs - 50)
        val each = (fillEnd - interlude.startMs) / 3
        return List(3) { i ->
            val start = interlude.startMs + each * i
            progress(start, if (i == 2) fillEnd else start + each, t)
        }
    }

    /** How far through [startMs]..[endMs] playback is at [t], from 0 to 1. */
    fun progress(startMs: Long, endMs: Long, t: Long): Float = when {
        t >= endMs -> 1f
        t <= startMs -> 0f
        else -> (t - startMs).toFloat() / (endMs - startMs)
    }
}
