package app.remastered.ui.wrapped

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.remastered.core.wrapped.Easing
import app.remastered.core.wrapped.interpolate
import app.remastered.core.wrapped.interpolateClamped
import app.remastered.core.wrapped.random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The web story's three.js scenes (remotion/three/scenes.tsx), drawn as 2.5D on a Compose Canvas.
 * Each uses the same camera, geometry and motion as its WebGL original, projected with the maths
 * in Three.kt: flat things (the CD, the album planes, confetti) through exact perspective, the
 * turntable as flat-shaded faces sorted back to front. Every value is a function of the frame.
 */

/** Drawing units per world unit for planes drawn through a homography. */
private const val UNIT = 1000.0

/** Draws [block] in the local drawing space of the z = 0 plane of [model]. */
private inline fun DrawScope.onPlane(camera: Camera, model: Mat4, block: DrawScope.() -> Unit) {
    val matrix = camera.planeMatrix(model, UNIT)
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.concat(matrix)
        block()
        canvas.nativeCanvas.restore()
    }
}

private fun annulus(inner: Double, outer: Double) = Path().apply {
    fillType = PathFillType.EvenOdd
    val o = (outer * UNIT).toFloat()
    val i = (inner * UNIT).toFloat()
    addOval(Rect(-o, -o, o, o))
    addOval(Rect(-i, -i, i, i))
}

private fun square(half: Double) = (half * UNIT).toFloat().let { Rect(-it, -it, it, it) }

private fun DrawScope.drawStretched(image: ImageBitmap, rect: Rect) = drawImage(
    image, IntOffset.Zero, IntSize(image.width, image.height),
    IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
)

// A CD's data side: silver, with a thin-film rainbow that slides around as the disc tilts, fine
// grooves and a couple of hard light streaks. The web's GLSL, in AGSL; `facing` is computed per
// pixel from the camera's position in the disc's own space.
private const val HOLO_AGSL = """
uniform float uTime;
uniform float3 uCam;
uniform float uUnit;
float3 hsv(float3 c) {
  float3 p = abs(fract(c.xxx + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(float3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
half4 main(float2 xy) {
  float2 p = float2(xy.x, -xy.y) / uUnit;
  float r = length(p);
  float a = atan(p.y, p.x);
  float facing = normalize(uCam - float3(p, 0.0)).z;
  float hue = fract(a / 6.2831 * 2.0 + r * 1.2 + facing * 0.9 + uTime * 0.08);
  float3 film = hsv(float3(hue, 0.6, 1.0));
  float grooves = 0.88 + 0.12 * sin(r * 520.0);
  float streak = pow(abs(cos(a + uTime * 0.6)), 60.0) * 0.9;
  float3 silver = float3(0.80, 0.82, 0.86);
  float3 col = mix(silver, film, 0.55 + 0.25 * (1.0 - facing)) * grooves + streak;
  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun holoShader() = RuntimeShader(HOLO_AGSL)

private val holoBlank = ThreeColor.basic(Color(0xFF111111))
private val holoHub = ThreeColor.basic(Color(0xFFE8E8E8), alpha = 0.6f)

/**
 * A spinning holographic CD with album art printed on its label (web `HoloDisc`). Before Android
 * 13 there are no runtime shaders, so the rainbow film is a sweep gradient over silver instead.
 */
@Composable
fun SlideFrame.HoloDisc(artUrl: String?, size: Int, modifier: Modifier = Modifier, enterAt: Int = 0, speed: Double = 1.0) {
    val art = assets.sceneImage(artUrl)
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) holoShader() else null }
    val f = frame.toDouble()
    Canvas(modifier.size(size.dp)) {
        val enter = interpolateClamped(f - enterAt, 0.0, 24.0, easing = Easing.out(Easing.back(1.6)))
        if (enter <= 0.0) return@Canvas
        val spin = -f * 0.045 * speed - (1 - enter) * 3
        val tilt = -0.35 + sin(f / 50) * 0.12
        val model = Mat4.scale(enter) * Mat4.euler(tilt, sin(f / 40) * 0.35, 0.0) * Mat4.rotationZ(spin)
        val camera = Camera(Vec3(0.0, 0.0, 4.4), 30.0, this.size.width, this.size.height)
        onPlane(camera, model) {
            val brush: Brush = if (shader != null && Build.VERSION.SDK_INT >= 33) {
                // The camera in the disc's own (unscaled, unrotated) space.
                val s2 = enter * enter
                val e = model.e
                val cam = camera.eye
                shader.setFloatUniform("uTime", (f / 30).toFloat())
                shader.setFloatUniform("uCam", ((e[0] * cam.x + e[1] * cam.y + e[2] * cam.z) / s2).toFloat(), ((e[4] * cam.x + e[5] * cam.y + e[6] * cam.z) / s2).toFloat(), ((e[8] * cam.x + e[9] * cam.y + e[10] * cam.z) / s2).toFloat())
                shader.setFloatUniform("uUnit", UNIT.toFloat())
                ShaderBrush(shader)
            } else {
                Brush.sweepGradient(listOf(Color(0xFFFF8FA3), Color(0xFFFFE08A), Color(0xFF9CFFB0), Color(0xFF8FD8FF), Color(0xFFC49BFF), Color(0xFFFF8FA3)), Offset.Zero)
            }
            if (shader == null) drawPath(annulus(0.16, 1.0), Color(0xFFCCD1DB))
            drawPath(annulus(0.16, 1.0), brush, alpha = if (shader == null) 0.6f else 1f)
            val label = annulus(0.16, 0.6)
            if (art != null) clipPath(label) { drawStretched(art, square(0.6)) } else drawPath(label, holoBlank)
            drawPath(annulus(0.13, 0.2), holoHub)
        }
    }
}

private val carouselLitBorder = ThreeColor.basic(Color.White)
private val carouselDimBorder = ThreeColor.basic(Color(0xFFBDBDBD))
private val carouselBlank = ThreeColor.basic(Color(0xFF333333))

/**
 * Album art on a turning ring, landing on each in turn: the ring eases from one to the next every
 * [holdFrames] (web `ArtCarousel`). Faces turned away are culled, as WebGL's front-side planes are.
 */
@Composable
fun SlideFrame.ArtCarousel(urls: List<String?>, width: Int, height: Int, holdFrames: Int, startAt: Int = 0, modifier: Modifier = Modifier) {
    val count = urls.size
    val local = max(0, frame - startAt)
    val index = min(count - 1, local / holdFrames)
    val within = (local - index * holdFrames).toDouble()
    // The first image is already facing; each later one swings in over 14 frames.
    val position = if (index == 0) 0.0 else interpolate(within, doubleArrayOf(0.0, 14.0), doubleArrayOf(index - 1.0, index.toDouble()), Easing.out(Easing.back(1.3)), right = app.remastered.core.wrapped.Extrapolate.Clamp)
    val step = 2 * PI / count
    Canvas(modifier.size(width.dp, height.dp)) {
        val camera = Camera(Vec3(0.0, 0.5, 6.4), 32.0, size.width, size.height)
        val group = Mat4.euler(0.08, -position * step, 0.0)
        val planes = urls.indices.map { i -> i to group * Mat4.rotationY(i * step) * Mat4.translation(0.0, 0.0, 2.1) }
            .filter { (_, m) -> camera.facing(m) }
            .sortedByDescending { (_, m) -> camera.depth(m.point(Vec3(0.0, 0.0, 0.0))) }
        for ((i, m) in planes) {
            val lit = i == index
            onPlane(camera, m * Mat4.translation(0.0, 0.0, -0.01)) { drawRect(if (lit) carouselLitBorder else carouselDimBorder, square(0.93).topLeft, square(0.93).size) }
            onPlane(camera, m) {
                val art = assets.sceneImage(urls[i], dim = !lit)
                if (art != null) drawStretched(art, square(0.85)) else drawRect(carouselBlank, square(0.85).topLeft, square(0.85).size)
            }
        }
    }
}

private val platterColor = Color(0xFF1A1A1A)
private val spindleRing = ThreeColor.basic(Color(0xFF555555))
private val LIGHT = Vec3(3.0, 6.0, 4.0)

/**
 * The listening clock as a turntable: 24 bars round a platter, each as tall as that hour's share,
 * the busiest in [accent]. It grows in, then turns (web `Turntable`). Lit like the original
 * (ambient 0.7, directional 2.2 from (3, 6, 4), standard material), shaded per face.
 */
@Composable
fun SlideFrame.Turntable(hours: List<Int>, peakHour: Int, width: Int, height: Int, accent: Color, base: Color, modifier: Modifier = Modifier) {
    val f = frame.toDouble()
    val maxMinutes = max(1, hours.maxOrNull() ?: 0)
    Canvas(modifier.size(width.dp, height.dp)) {
        val camera = Camera(Vec3(0.0, 4.4, 6.6), 34.0, size.width, size.height)
        val group = Mat4.rotationY(interpolate(f, doubleArrayOf(0.0, 180.0), doubleArrayOf(0.6, -0.4)))

        fun face(points: List<Vec3>, normal: Vec3, color: Color, roughness: Double) {
            val center = points.fold(Vec3(0.0, 0.0, 0.0)) { a, p -> a + p } * (1.0 / points.size)
            val toCamera = camera.eye - center
            if ((normal dot toCamera) <= 0) return
            val shade = ThreeColor.standard(color, normal, toCamera, roughness, 0.7, 2.2, LIGHT)
            val path = Path()
            points.forEachIndexed { k, p ->
                val (x, y) = camera.project(p)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, shade)
            // Hairline in the same colour hides antialiasing seams between neighbouring faces.
            drawPath(path, shade, style = Stroke(0.75f))
        }

        // Platter: a 96-sided cylinder, its top at y = 0.
        val segments = 96
        val radius = 2.25
        val ring = (0..segments).map { k -> 2 * PI * k / segments }
        for (k in 0 until segments) {
            val a0 = ring[k]
            val a1 = ring[k + 1]
            val mid = (a0 + a1) / 2
            face(
                listOf(Vec3(sin(a0) * radius, 0.0, cos(a0) * radius), Vec3(sin(a1) * radius, 0.0, cos(a1) * radius), Vec3(sin(a1) * radius, -0.12, cos(a1) * radius), Vec3(sin(a0) * radius, -0.12, cos(a0) * radius)).map(group::point),
                group.direction(Vec3(sin(mid), 0.0, cos(mid))), platterColor, 0.5,
            )
        }
        face(ring.dropLast(1).map { group.point(Vec3(sin(it) * radius, 0.0, cos(it) * radius)) }, Vec3(0.0, 1.0, 0.0), platterColor, 0.5)

        // The spindle ring, flat on top.
        val spindle = Path().apply {
            fillType = PathFillType.EvenOdd
            for (r in listOf(0.36, 0.3)) {
                (0 until 48).forEach { k ->
                    val a = 2 * PI * k / 48
                    val (x, y) = camera.project(group.point(Vec3(sin(a) * r, 0.005, cos(a) * r)))
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
        drawPath(spindle, spindleRing)

        // Bars, farthest first. Midnight at the back, the day running clockwise seen from above.
        val bars = hours.mapIndexed { hour, minutes ->
            val grow = interpolateClamped(f - 8 - hour * 1.2, 0.0, 16.0, easing = Easing.out(Easing.cubic))
            val h = max(0.04, minutes.toDouble() / maxMinutes * 1.7 * grow)
            val a = -(hour / 24.0) * PI * 2 + PI
            val model = group * Mat4.translation(sin(a) * 1.55, h / 2, cos(a) * 1.55) * Mat4.rotationY(a)
            Triple(hour, h, model)
        }.sortedByDescending { (_, _, m) -> camera.depth(m.point(Vec3(0.0, 0.0, 0.0))) }
        for ((hour, h, m) in bars) {
            val color = if (hour == peakHour) accent else base
            val hx = 0.13
            val hy = h / 2
            val hz = 0.25
            fun c(x: Double, y: Double, z: Double) = m.point(Vec3(x * hx, y * hy, z * hz))
            val faces = listOf(
                Vec3(1.0, 0.0, 0.0) to listOf(c(1.0, -1.0, 1.0), c(1.0, -1.0, -1.0), c(1.0, 1.0, -1.0), c(1.0, 1.0, 1.0)),
                Vec3(-1.0, 0.0, 0.0) to listOf(c(-1.0, -1.0, -1.0), c(-1.0, -1.0, 1.0), c(-1.0, 1.0, 1.0), c(-1.0, 1.0, -1.0)),
                Vec3(0.0, 0.0, 1.0) to listOf(c(-1.0, -1.0, 1.0), c(1.0, -1.0, 1.0), c(1.0, 1.0, 1.0), c(-1.0, 1.0, 1.0)),
                Vec3(0.0, 0.0, -1.0) to listOf(c(1.0, -1.0, -1.0), c(-1.0, -1.0, -1.0), c(-1.0, 1.0, -1.0), c(1.0, 1.0, -1.0)),
                Vec3(0.0, 1.0, 0.0) to listOf(c(-1.0, 1.0, 1.0), c(1.0, 1.0, 1.0), c(1.0, 1.0, -1.0), c(-1.0, 1.0, -1.0)),
            )
            for ((n, pts) in faces) face(pts, m.direction(n), color, 0.35)
        }
    }
}

/** Paper confetti tumbling down, for the big moments (web `Confetti`). */
@Composable
fun SlideFrame.Confetti(width: Int, height: Int, colors: List<Color>, count: Int = 70, seed: String = "confetti", modifier: Modifier = Modifier) {
    val t = frame / 30.0
    val shades = remember(colors) { colors.map { ThreeColor.basic(it) } }
    Canvas(modifier.size(width.dp, height.dp)) {
        val camera = Camera(Vec3(0.0, 0.0, 10.0), 50.0, size.width, size.height)
        val pieces = (0 until count).map { i ->
            val x = (random("${seed}x$i") - 0.5) * 11
            val fall = 1.2 + random("${seed}s$i") * 1.6
            val y = 9 - ((random("${seed}y$i") * 22 + t * fall) % 22)
            val spin = t * (1 + random("${seed}r$i") * 3)
            val model = Mat4.translation(x + sin(t + i) * 0.3, y, (random("${seed}z$i") - 0.5) * 4) * Mat4.euler(spin, spin * 0.7, i.toDouble())
            i to model
        }.sortedByDescending { (_, m) -> camera.depth(m.point(Vec3(0.0, 0.0, 0.0))) }
        for ((i, m) in pieces) {
            val path = Path()
            listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).forEachIndexed { k, (sx, sy) ->
                val (px, py) = camera.project(m.point(Vec3(sx * 0.16, sy * 0.09, 0.0)))
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, shades[i % shades.size])
        }
    }
}
