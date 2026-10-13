package app.remastered.core.lyrics

enum class LineState { Upcoming, Active, Past }

/** Where playback is within synced lyrics. Pure functions of the lines and a time in ms. */
object LyricsTimeline {
    /** Each line's state at [t]. Lines can overlap, so more than one may be [LineState.Active]. */
    fun states(lines: List<LyricLine>, t: Long): List<LineState> = lines.map {
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
    fun focus(lines: List<LyricLine>, t: Long): Int {
        if (lines.isEmpty()) return -1
        lines.indexOfFirst { t >= it.startMs && t < it.endMs }.let { if (it >= 0) return it }
        return lines.indexOfLast { it.startMs <= t }.coerceAtLeast(0)
    }

    /** How far through [startMs]..[endMs] playback is at [t], from 0 to 1. */
    fun progress(startMs: Long, endMs: Long, t: Long): Float = when {
        t >= endMs -> 1f
        t <= startMs -> 0f
        else -> (t - startMs).toFloat() / (endMs - startMs)
    }
}
