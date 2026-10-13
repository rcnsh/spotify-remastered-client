package app.remastered.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

// Charts drawn with Canvas in the theme's colours. Each takes plain numbers; the math is in StatsMath.

/** Series colours for stacked charts and legends, starting from the accent. */
val SeriesColors = listOf(
    Palette.accent, Color(0xFFE5B567), Color(0xFF4FC3C9), Palette.heart, Color(0xFFB57EDC),
    Palette.progress, Palette.accentLight, Color(0xFFE88A5A), Color(0xFF8E94AA),
)

/**
 * Vertical bars, one per value, scaled to [max] (default: the largest). Tapping a bar calls
 * [onTap] with its index; [highlight] draws one bar brighter.
 */
@Composable
fun Bars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    max: Float? = null,
    color: Color = Palette.accent,
    highlight: Int? = null,
    gridLines: Int = 0,
    onTap: ((Int) -> Unit)? = null,
) {
    val top = max ?: values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val tap = if (onTap != null && values.isNotEmpty()) Modifier.pointerInput(values) {
        detectTapGestures { o -> onTap((o.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)) }
    } else Modifier
    Canvas(modifier.then(tap)) {
        for (g in 1..gridLines) {
            val y = size.height * (1f - g.toFloat() / gridLines)
            drawLine(Palette.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val gap = when {
            slot > 10.dp.toPx() -> slot * .28f
            slot > 3.dp.toPx() -> slot * .18f
            else -> 0f
        }
        val w = (slot - gap).coerceAtLeast(1f)
        val radius = minOf(3.dp.toPx(), w / 2)
        values.forEachIndexed { i, v ->
            val h = (v / top).coerceIn(0f, 1f) * size.height
            if (h <= 0f) return@forEachIndexed
            val c = when {
                highlight == null -> color
                i == highlight -> Palette.accentLight
                else -> color.copy(alpha = .55f)
            }
            drawRoundRect(c, Offset(i * slot + gap / 2, size.height - h), Size(w, h), CornerRadius(radius, radius))
        }
    }
}

/** Labels spread under a chart: first at the start, last at the end. */
@Composable
fun AxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = Type.caption, color = Palette.dim, maxLines = 1) }
    }
}

/** A small line chart with a soft fill, for months of plays. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    val points = scaled(values)
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        fun at(i: Int) = Offset(
            if (points.size == 1) size.width / 2 else i * size.width / (points.size - 1),
            pad + h * (1f - points[i]),
        )
        val line = Path().apply { points.indices.forEach { if (it == 0) moveTo(at(it).x, at(it).y) else lineTo(at(it).x, at(it).y) } }
        if (points.size > 1) {
            val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .35f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(color, 3.dp.toPx(), at(0))
        }
    }
}

/** 24 bars for the share of plays in each hour, with 00/06/12/18 under them. */
@Composable
fun HourChart(shares: List<Double>, modifier: Modifier = Modifier) {
    val peak = shares.indices.maxByOrNull { shares[it] }
    Column(modifier) {
        Bars(shares.map { it.toFloat() }, Modifier.fillMaxWidth().height(96.dp), highlight = peak, gridLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("00", "06", "12", "18").forEach { Text(it, style = Type.caption, color = Palette.dim, modifier = Modifier.weight(1f)) }
        }
    }
}

private val Weekdays = listOf("M", "T", "W", "T", "F", "S", "S")

/** Monday-first 7 × 24 grid of shares; darker is quieter. */
@Composable
fun WeekHeatmap(grid: List<List<Double>>, modifier: Modifier = Modifier) {
    val max = grid.flatten().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Row {
            Column(Modifier.width(16.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                Weekdays.forEach { Text(it, style = Type.caption, color = Palette.dim, modifier = Modifier.height(16.dp)) }
            }
            Canvas(Modifier.weight(1f).height(16.dp * 7)) {
                val cw = size.width / 24
                val ch = size.height / 7
                val gap = 1.5.dp.toPx()
                grid.take(7).forEachIndexed { d, row ->
                    row.take(24).forEachIndexed { hr, v ->
                        val a = (v / max).toFloat().coerceIn(0f, 1f)
                        drawRoundRect(
                            if (a <= 0f) Palette.raised else Palette.accent.copy(alpha = .12f + .88f * a),
                            Offset(hr * cw + gap / 2, d * ch + gap / 2), Size(cw - gap, ch - gap), CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp)) {
            listOf("00", "06", "12", "18").forEach { Text(it, style = Type.caption, color = Palette.dim, modifier = Modifier.weight(1f)) }
        }
    }
}

/** Stacked areas from [stackShares] bands, one colour per series. */
@Composable
fun StackedArea(bands: List<List<Pair<Float, Float>>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawLine(Palette.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        drawLine(Palette.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        bands.forEachIndexed { i, band ->
            if (band.isEmpty()) return@forEachIndexed
            fun x(q: Int) = if (band.size == 1) size.width / 2 else q * size.width / (band.size - 1)
            fun y(v: Float) = size.height * (1f - v)
            val path = Path().apply {
                moveTo(x(0), y(band[0].second))
                for (q in 1..band.lastIndex) lineTo(x(q), y(band[q].second))
                for (q in band.lastIndex downTo 0) lineTo(x(q), y(band[q].first))
                close()
            }
            drawPath(path, SeriesColors[i % SeriesColors.size].copy(alpha = .85f))
        }
    }
}

/** A thin horizontal bar filled to [fraction] (0–1). */
@Composable
fun ShareBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    Canvas(modifier.height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.raised, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(maxOf(w, size.height), size.height), cornerRadius = r)
    }
}
