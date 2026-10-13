package app.remastered.ui.wrapped

import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.SpringConfig
import app.remastered.core.wrapped.Springs
import app.remastered.core.wrapped.WrappedTimeline
import app.remastered.core.wrapped.fitText
import app.remastered.core.wrapped.fitTextOnNLines
import app.remastered.core.wrapped.interpolateClamped
import app.remastered.core.wrapped.random
import app.remastered.core.wrapped.spring
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Collage primitives (web: remotion/ui/collage.tsx). Inside WrappedFrame one dp is one logical
 * pixel of the 1080 × 1920 story and one sp is one logical pixel of type, so sizes here are the
 * web story's CSS pixels unchanged. Everything stacks in source order, as the web's DOM does.
 */

/** One slide at one of its frames: what a slide composable draws from. */
class SlideFrame(
    /** Frame within this slide (0 = the first frame of its entrance). */
    val frame: Int,
    val durationInFrames: Int,
    val data: Wrapped,
    val assets: WrappedAssets,
    val measure: PosterMeasure,
) {
    /** A spring from 0 to 1 starting at [delay] frames (web `useEnter`). */
    fun enter(delay: Int = 0, config: SpringConfig = Springs.snappy): Float = spring((frame - delay).toDouble(), WrappedTimeline.FPS, config).toFloat()

    /**
     * Stop-motion wobble: a small offset and turn that changes every [every] frames, like
     * hand-moved paper cut-outs (web `useJitter`).
     */
    fun jitter(seed: String, amount: Double = 1.0, every: Int = 4): Jitter {
        val step = floor(frame.toDouble() / every).toInt()
        return Jitter(
            ((random("${seed}x$step") - 0.5) * 6 * amount).toFloat(),
            ((random("${seed}y$step") - 0.5) * 6 * amount).toFloat(),
            ((random("${seed}r$step") - 0.5) * 1.6 * amount).toFloat(),
        )
    }
}

data class Jitter(val x: Float, val y: Float, val r: Float)

// --- Layout: CSS absolute positioning ---

/**
 * `position: absolute; left: x; top: y` — placed at an offset, taking no space in its parent.
 * Measured against the parent's limits, as CSS shrink-to-fit is.
 */
fun Modifier.at(x: Number, y: Number) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) { p.place(x.toFloat().dp.roundToPx(), y.toFloat().dp.roundToPx()) }
}

/** `position: absolute; left; bottom` inside a full-frame parent. */
fun Modifier.pinBottom(left: Number, bottom: Number) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        p.place(left.toFloat().dp.roundToPx(), constraints.maxHeight - bottom.toFloat().dp.roundToPx() - p.height)
    }
}

/**
 * A CSS `transform` (translate, then scale, then rotate) about [origin], with optional opacity.
 * Translation is in logical px.
 */
fun Modifier.css(
    tx: Float = 0f,
    ty: Float = 0f,
    scale: Float = 1f,
    rotate: Float = 0f,
    origin: TransformOrigin = TransformOrigin.Center,
    opacity: Float = 1f,
) = graphicsLayer {
    translationX = tx.dp.toPx()
    translationY = ty.dp.toPx()
    scaleX = scale
    scaleY = scale
    rotationZ = rotate
    transformOrigin = origin
    alpha = opacity.coerceIn(0f, 1f)
}

val LeftTop = TransformOrigin(0f, 0f)
val LeftCenter = TransformOrigin(0f, 0.5f)
val LeftBottom = TransformOrigin(0f, 1f)

/**
 * A column of text blocks pinned to the bottom of the frame. Each block pushes the next one down
 * rather than sitting at a fixed height, so a name that wraps to two lines can never land on the
 * line below it (web `Stack`).
 */
@Composable
fun Stack(left: Number = 80, bottom: Number = 150, width: Number = 920, gap: Number = 24, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.pinBottom(left, bottom).then(modifier).width(width.toFloat().dp),
        verticalArrangement = Arrangement.spacedBy(gap.toFloat().dp),
        content = content,
    )
}

// --- Type ---

/**
 * A text block laid out the way CSS lays it out: lines wrap at the available width (unless
 * [wrap] is off), each line box is [lineHeight] em tall (the font's own height when null) and the
 * glyphs sit centred in it, so a line height smaller than the font's ascent + descent pulls lines
 * together and lets glyphs overhang the box, exactly like `line-height: 0.86`. Compose's own
 * lineHeight doesn't shrink below the font's height, so lines are placed here by hand.
 *
 * The block is as wide as its widest line (CSS fit-content), or the exact width it's given.
 */
@Composable
fun CssText(
    text: String,
    family: FontFamily,
    size: Number,
    color: Color = Ink,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    lineHeight: Double? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    align: TextAlign = TextAlign.Start,
    wrap: Boolean = true,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val style = TextStyle(fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.toFloat().sp, color = color, letterSpacing = letterSpacing)
    val lines = remember { arrayOf<List<TextLayoutResult>>(emptyList()) }
    val tops = remember { arrayOf(FloatArray(0)) }
    val lefts = remember { arrayOf(FloatArray(0)) }
    Layout(
        modifier.drawBehind {
            lines[0].forEachIndexed { i, line -> drawText(line, topLeft = Offset(lefts[0][i], tops[0][i])) }
        },
    ) { _, constraints ->
        val maxWidth = if (wrap && constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val full = measurer.measure(text, style, softWrap = wrap, constraints = Constraints(maxWidth = maxWidth))
        val laid = (0 until full.lineCount).map { i ->
            val piece = text.substring(full.getLineStart(i), full.getLineEnd(i, visibleEnd = true))
            measurer.measure(piece, style, softWrap = false)
        }
        val widest = laid.maxOfOrNull { it.size.width } ?: 0
        val width = if (constraints.hasFixedWidth) constraints.maxWidth else widest.coerceIn(constraints.minWidth, constraints.maxWidth)
        val lineBox = lineHeight?.let { (it * size.toFloat().sp.toPx()).toFloat() }
        var y = 0f
        val lineTops = FloatArray(laid.size)
        val lineLefts = FloatArray(laid.size)
        laid.forEachIndexed { i, line ->
            val natural = line.size.height.toFloat()
            val box = lineBox ?: natural
            lineTops[i] = y + (box - natural) / 2
            lineLefts[i] = when (align) {
                TextAlign.Center -> (width - line.size.width) / 2f
                TextAlign.End, TextAlign.Right -> (width - line.size.width).toFloat()
                else -> 0f
            }
            y += box
        }
        lines[0] = laid
        tops[0] = lineTops
        lefts[0] = lineLefts
        val baseline = laid.firstOrNull()?.let { (lineTops[0] + it.firstBaseline).roundToInt() } ?: 0
        layout(width, y.roundToInt(), mapOf(FirstBaseline to baseline)) {}
    }
}

/** Condensed poster type sized to exactly fill [width] (web `Poster`). */
@Composable
fun SlideFrame.Poster(text: String, width: Number, max: Number = 900, color: Color = Ink, modifier: Modifier = Modifier, upper: Boolean = true) {
    val shown = if (upper) text.uppercase() else text
    val size = min(max.toDouble(), fitText(shown, width.toDouble(), measure))
    CssText(shown, WrappedFonts.poster, size, color, modifier.width(width.toFloat().dp), lineHeight = 0.86, wrap = false)
}

/** Poster type wrapped onto at most [lines] lines, each filling the width it can (web `PosterLines`). */
@Composable
fun SlideFrame.PosterLines(text: String, width: Number, lines: Int = 3, max: Number = 300, color: Color = Ink, modifier: Modifier = Modifier) {
    val shown = text.uppercase()
    val fit = remember(shown, width, lines, max) { fitTextOnNLines(shown, width.toDouble(), lines, max.toDouble(), measure) }
    Column(modifier.width(width.toFloat().dp)) {
        fit.lines.forEach { CssText(it, WrappedFonts.poster, fit.fontSize, color, lineHeight = 0.9, wrap = false) }
    }
}

/** A handwritten aside (web `Marker`). */
@Composable
fun Marker(text: String, size: Number = 64, color: Color = Ink, rotate: Float = -4f, modifier: Modifier = Modifier, opacity: Float = 1f) {
    CssText(text, WrappedFonts.marker, size, color, modifier.css(rotate = rotate, opacity = opacity), lineHeight = 1.05)
}

/** Receipt-style figures (web `Mono`). */
@Composable
fun Mono(text: String, size: Number = 36, color: Color = Ink, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, wrap: Boolean = true) {
    CssText(text, WrappedFonts.mono, size, color, modifier, lineHeight = 1.35, letterSpacing = 0.02.em, align = align, wrap = wrap)
}

/** Running text in Archivo, CSS `line-height: normal` unless given. */
@Composable
fun Body(text: String, size: Number, weight: Int, color: Color = Ink, modifier: Modifier = Modifier, lineHeight: Double? = null) {
    CssText(text, WrappedFonts.body, size, color, modifier, weight = weight, lineHeight = lineHeight)
}

/** A label slapped on at an angle, highlighter-style (web `Sticker`). */
@Composable
fun Sticker(text: String, color: Color, ink: Color = Ink, rotate: Float = -3f, size: Number = 56, modifier: Modifier = Modifier) {
    CssText(
        text.uppercase(), WrappedFonts.body, size, ink,
        modifier.css(rotate = rotate).background(color).padding(start = 22.dp, top = 6.dp, end = 22.dp, bottom = 10.dp),
        weight = 900, letterSpacing = (-0.01).em, wrap = false,
    )
}

// --- Paper ---

/** A clip-path polygon in percentages of the box (CSS `polygon(x% y%, …)`). */
private fun percentPolygon(points: List<Pair<Double, Double>>) = GenericShape { size, _ ->
    points.forEachIndexed { i, (x, y) ->
        val px = (x / 100 * size.width).toFloat()
        val py = (y / 100 * size.height).toFloat()
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/** A strip of masking tape with torn ends (web `Tape`). Its shadow is clipped away, as on the web. */
@Composable
fun Tape(x: Number, y: Number, rotate: Float, width: Number = 220, color: Color = rgba(241, 237, 228, 0.82), seed: String = "tape") {
    val w = width.toFloat()
    val shape = remember(seed) {
        val teeth = 6
        val left = ArrayList<Pair<Double, Double>>()
        val right = ArrayList<Pair<Double, Double>>()
        for (i in 0..teeth) {
            val t = i.toDouble() / teeth * 100
            left.add(random("${seed}l$i") * 5 to t)
            right.add(0, 100 - random("${seed}r$i") * 5 to t)
        }
        percentPolygon(left + right)
    }
    Box(Modifier.at(x.toFloat() - w / 2, y.toFloat() - 28).size(w.dp, 56.dp).css(rotate = rotate).clip(shape).background(color))
}

/** A jagged paper edge across the top and bottom of a box (web `tornPolygon`). */
private fun tornShape(seed: String, teeth: Int = 28, depth: Double = 2.4) = run {
    val top = ArrayList<Pair<Double, Double>>()
    val bottom = ArrayList<Pair<Double, Double>>()
    for (i in 0..teeth) {
        val x = i.toDouble() / teeth * 100
        top.add(x to random("${seed}t$i") * depth)
        bottom.add(0, x to 100 - random("${seed}b$i") * depth)
    }
    percentPolygon(top + bottom)
}

/** A torn strip of paper holding its content (web `Torn`). */
@Composable
fun Torn(
    modifier: Modifier = Modifier,
    color: Color = Paper,
    seed: String = "torn",
    depth: Double = 2.4,
    padding: PaddingValues = PaddingValues(horizontal = 44.dp, vertical = 34.dp),
    content: @Composable () -> Unit,
) {
    val shape = remember(seed, depth) { tornShape(seed, 28, depth) }
    Box(modifier.clip(shape).background(color).padding(padding)) { content() }
}

/** Draws [image] to fill the whole box, cropped to cover (CSS `object-fit: cover`). */
fun DrawScope.drawCover(image: ImageBitmap, dst: Size = size, at: Offset = Offset.Zero) {
    val scale = maxOf(dst.width / image.width, dst.height / image.height)
    val sw = dst.width / scale
    val sh = dst.height / scale
    val sx = (image.width - sw) / 2
    val sy = (image.height - sh) / 2
    drawImage(
        image,
        srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
        srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
    )
}

/**
 * A photo cut out and stuck down: white border, slight turn, drop shadow. A flat block when
 * there's no image (web `Cutout`).
 */
@Composable
fun Cutout(image: ImageBitmap?, size: Number, rotate: Float = 0f, border: Number = 14, modifier: Modifier = Modifier, fallback: Color = Color(0xFF222222)) {
    Box(
        modifier
            .css(rotate = rotate)
            .size(size.toFloat().dp)
            .dropShadow(RectangleShape, Shadow(radius = 40.dp, color = Color.Black, offset = DpOffset(0.dp, 18.dp), alpha = 0.35f))
            .background(Color(0xFFFBFAF6))
            .padding(border.toFloat().dp)
            .drawBehind { if (image != null) drawCover(image) else drawRect(fallback) },
    )
}

// --- Doodles ---

/** A ready-made doodle: an SVG path, its (authored) length, and its viewBox. */
class DoodleSpec(val d: String, val length: Float, val viewWidth: Float, val viewHeight: Float) {
    internal val path by lazy { PathParser().parsePathString(d).toPath() }
}

/** Ready-made doodles, in a 100×100 (or wider) box (web `DOODLES`). */
object Doodles {
    val circle = DoodleSpec("M50 6 C 80 4, 98 26, 96 52 C 94 80, 70 96, 46 94 C 18 92, 2 70, 6 44 C 9 22, 30 8, 58 10", 300f, 100f, 100f)
    val underline = DoodleSpec("M2 12 C 40 4, 90 18, 140 8 S 230 4, 296 12", 310f, 300f, 20f)
    val arrow = DoodleSpec("M6 90 C 30 60, 50 40, 88 14 M 62 12 L 90 12 L 84 40", 190f, 100f, 100f)
    val star = DoodleSpec("M50 4 L 61 38 L 96 38 L 68 59 L 79 94 L 50 72 L 21 94 L 32 59 L 4 38 L 39 38 Z", 360f, 100f, 100f)
    val squiggle = DoodleSpec("M4 50 C 16 20, 28 80, 40 50 S 64 20, 76 50 S 100 80, 112 50 S 136 20, 148 50", 240f, 152f, 100f)
    val burst = DoodleSpec("M50 2 L 50 22 M50 78 L 50 98 M2 50 L 22 50 M78 50 L 98 50 M16 16 L 30 30 M70 70 L 84 84 M84 16 L 70 30 M30 70 L 16 84", 400f, 100f, 100f)
}

/**
 * A marker doodle drawn on over [duration] frames: the dash offset runs from the path's full length
 * to zero (web `Doodle`). Fits its box like SVG (`xMidYMid meet`) unless [stretch].
 */
@Composable
fun SlideFrame.Doodle(spec: DoodleSpec, x: Number, y: Number, width: Number, height: Number, color: Color = Ink, stroke: Float = 10f, delay: Int = 0, duration: Int = 14, stretch: Boolean = false) {
    val drawn = interpolateClamped((frame - delay).toDouble(), 0.0, duration.toDouble()).toFloat()
    if (drawn == 0f) return
    Canvas(Modifier.at(x, y).size(width.toFloat().dp, height.toFloat().dp)) {
        var sx = size.width / spec.viewWidth
        var sy = size.height / spec.viewHeight
        var dx = 0f
        var dy = 0f
        if (!stretch) {
            val s = min(sx, sy)
            dx = (size.width - spec.viewWidth * s) / 2
            dy = (size.height - spec.viewHeight * s) / 2
            sx = s
            sy = s
        }
        withTransform({ translate(dx, dy); scale(sx, sy, Offset.Zero) }) {
            drawPath(
                spec.path, color,
                style = Stroke(
                    width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(spec.length, spec.length), spec.length * (1 - drawn)),
                ),
            )
        }
    }
}

// --- Grain ---

/** Photocopy grain over everything, shifting at 12 fps like film (web `Grain`). [frame] is the story's frame. */
@Composable
fun Grain(frame: Int, opacity: Float = 0.5f) {
    val paint = remember {
        Paint().apply {
            shader = BitmapShader(WrappedAssets.grain.asAndroidBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
    }
    val matrix = remember { android.graphics.Matrix() }
    Canvas(Modifier.fillMaxSize()) {
        val step = floor(frame / 2.5).toInt()
        val dx = floor(random("gx$step") * 256).toFloat()
        val dy = floor(random("gy$step") * 256).toFloat()
        val unit = 1.dp.toPx()
        paint.alpha = (opacity * 255).roundToInt()
        matrix.setScale(unit, unit)
        matrix.postTranslate(dx * unit, dy * unit)
        paint.shader.setLocalMatrix(matrix)
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}
