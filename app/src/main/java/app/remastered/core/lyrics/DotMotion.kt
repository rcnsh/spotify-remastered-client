package app.remastered.core.lyrics

/** How an interlude dot looks: size as a multiple of its own, lift in dot heights, and brightness 0 to 1. */
data class DotLook(val scale: Float, val lift: Float, val brightness: Float)

/**
 * The Spicy Lyrics extension's interlude dot: no repeating bounce. Over its own third of the gap
 * a dot grows, brightens and rises slowly, then settles back and stays lit. Its curves are natural
 * cubic splines through the extension's points, as there, so the rise overshoots its middle point:
 * it peaks about .6 of the way through at about .63 dot heights.
 */
object DotMotion {
    private val scale = Spline(floatArrayOf(0f, .7f, 1f), floatArrayOf(.75f, 1.05f, 1f))
    // The extension rises .12 of the lyric font size; our dots are about .45 of it.
    private val lift = Spline(floatArrayOf(0f, .9f, 1f), floatArrayOf(0f, .27f, 0f))
    private val brightness = Spline(floatArrayOf(0f, .6f, 1f), floatArrayOf(0f, 1f, 1f))

    /** The look of a dot [fill] of the way through its turn, from [LyricsTimeline.dots]. */
    fun look(fill: Float): DotLook = DotLook(scale.at(fill), lift.at(fill), brightness.at(fill).coerceIn(0f, 1f))
}

/** A natural cubic spline through ([xs], [ys]), held flat past either end. */
class Spline(private val xs: FloatArray, private val ys: FloatArray) {
    // Second derivatives at each point, zero at the ends.
    private val m = FloatArray(xs.size)

    init {
        require(xs.size == ys.size && xs.size >= 2)
        val n = xs.size - 1
        // Tridiagonal solve (Thomas) for m[1] until m[n - 1].
        val c = FloatArray(xs.size)
        val d = FloatArray(xs.size)
        for (i in 1 until n) {
            val h0 = xs[i] - xs[i - 1]
            val h1 = xs[i + 1] - xs[i]
            val r = 6 * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
            val b = 2 * (h0 + h1) - h0 * c[i - 1]
            c[i] = h1 / b
            d[i] = (r - h0 * d[i - 1]) / b
        }
        for (i in n - 1 downTo 1) m[i] = d[i] - c[i] * m[i + 1]
    }

    fun at(x: Float): Float {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val i = (0 until xs.size - 1).first { x <= xs[it + 1] }
        val h = xs[i + 1] - xs[i]
        val a = xs[i + 1] - x
        val b = x - xs[i]
        return m[i] * a * a * a / (6 * h) + m[i + 1] * b * b * b / (6 * h) +
            (ys[i] / h - m[i] * h / 6) * a + (ys[i + 1] / h - m[i + 1] * h / 6) * b
    }
}
