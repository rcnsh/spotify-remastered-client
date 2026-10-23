package app.remastered.ui.lyrics

/**
 * Lyric lines picked for a share card: one to [MAX] consecutive lines, as indexes into
 * `Lyrics.lines`. A long press starts one ([LineSelection.start]); taps then grow or shrink it.
 */
data class LineSelection(val first: Int, val last: Int) {
    init { require(first in 0..last && size <= MAX) { "bad selection $first..$last" } }

    val size: Int get() = last - first + 1

    operator fun contains(line: Int) = line in first..last

    /** Whether a tap on [line] would add it: it's next to the selection and there's room. */
    fun canAdd(line: Int) = size < MAX && (line == first - 1 || line == last + 1)

    /**
     * After a tap on [line]: a neighbour is added (up to [MAX]), an end line is removed, a line
     * inside cuts the selection off after it, and a line further away starts over from it. Null
     * when the only selected line is tapped: selection ends.
     */
    fun tap(line: Int): LineSelection? = when {
        line == first && line == last -> null
        line == first -> copy(first = first + 1)
        line == last -> copy(last = last - 1)
        line in this -> copy(last = line)
        canAdd(line) -> LineSelection(minOf(first, line), maxOf(last, line))
        // Next to a full selection: keep it rather than lose what was picked.
        line == first - 1 || line == last + 1 -> this
        else -> start(line)
    }

    /** The selected lines' text, one entry per line, blank lines dropped. */
    fun texts(lines: List<String>): List<String> =
        lines.subList(first.coerceAtMost(lines.size), (last + 1).coerceAtMost(lines.size)).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** Most lines a card holds: a quote, not the song. */
        const val MAX = 4

        fun start(line: Int) = LineSelection(line, line)
    }
}

/** How the share card's lyrics are sized: the biggest text that fits, never smaller than a floor. */
object ShareCardText {
    /** Text sizes tried, largest first, in pixels on the 1080 px wide card. */
    val Sizes = listOf(96f, 84f, 72f, 64f, 56f, 50f, 44f)

    /**
     * The largest of [sizes] at which the lyrics, laid out by [heightAt] (pixels tall at a text
     * size), fit in [maxHeight]. The smallest size when none does: the renderer then cuts the text
     * off with an ellipsis at [maxLines].
     */
    fun fit(maxHeight: Float, sizes: List<Float> = Sizes, heightAt: (Float) -> Float): Float =
        sizes.firstOrNull { heightAt(it) <= maxHeight } ?: sizes.last()

    /** Rows of text that fit in [maxHeight] at [lineHeight] each, at least one. */
    fun maxLines(maxHeight: Float, lineHeight: Float): Int = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
}
