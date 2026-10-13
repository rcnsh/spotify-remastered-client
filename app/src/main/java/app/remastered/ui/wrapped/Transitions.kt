package app.remastered.ui.wrapped

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import app.remastered.core.wrapped.Easing
import app.remastered.core.wrapped.WrappedTimeline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Wrapped's rotating geometric masks: the incoming slide grows out of a turning shape until it
 * covers the frame (web remotion/ui/transitions.tsx).
 */
enum class MaskShape { Star, Square, Triangle, Circle, Burst }

/** Cycled through, so consecutive transitions never repeat a shape (web Wrapped.tsx `SHAPES`). */
private val SHAPES = listOf(MaskShape.Star, MaskShape.Square, MaskShape.Circle, MaskShape.Triangle, MaskShape.Burst)

/** The wipe that brings in the slide at [index] (> 0), and its turns. */
fun transitionFor(index: Int): Pair<MaskShape, Double> = SHAPES[index % SHAPES.size] to (if (index % 2 == 0) 0.4 else -0.4)

private val DIAGONAL = hypot(WrappedTimeline.WIDTH.toDouble(), WrappedTimeline.HEIGHT.toDouble())

private fun ring(n: Int, inner: Double?, offset: Double = -PI / 2): List<Pair<Double, Double>> {
    val total = if (inner == null) n else n * 2
    return List(total) { i ->
        val r = if (inner != null && i % 2 == 1) inner else 1.0
        val a = offset + i.toDouble() / total * PI * 2
        cos(a) * r to sin(a) * r
    }
}

private val POINTS = mapOf(
    MaskShape.Star to ring(5, 0.48),
    MaskShape.Burst to ring(12, 0.72),
    MaskShape.Square to ring(4, null, PI / 4),
    MaskShape.Triangle to ring(3, null),
    MaskShape.Circle to ring(48, null),
)

/**
 * The clip for the entering slide at linear [presentationProgress] (0..1), or null once it covers
 * the frame. Coordinates are logical px scaled to whatever size the shape is applied at.
 */
fun shapeWipe(shape: MaskShape, turns: Double, presentationProgress: Double): Shape? {
    val progress = Easing.inOut(Easing.cubic)(presentationProgress)
    if (progress >= 1) return null
    // Large enough at the end that even the star's inner corners clear the frame.
    val scale = progress * DIAGONAL * 1.25
    val rotation = progress * PI * 2 * turns
    val c = cos(rotation)
    val s = sin(rotation)
    val points = POINTS.getValue(shape)
    return GenericShape { size, _ ->
        val k = size.width / WrappedTimeline.WIDTH
        points.forEachIndexed { i, (x, y) ->
            val px = ((WrappedTimeline.WIDTH / 2.0 + (x * c - y * s) * scale) * k).toFloat()
            val py = ((WrappedTimeline.HEIGHT / 2.0 + (x * s + y * c) * scale) * k).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
