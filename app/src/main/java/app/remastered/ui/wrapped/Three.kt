package app.remastered.ui.wrapped

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Just enough of three.js to draw the web story's scenes as 2.5D: column-major 4×4 matrices built
 * the way three builds them, a perspective camera that looks at the origin (react-three-fiber's
 * default), ACES Filmic tone mapping with sRGB output (also r3f's default) and three's physical
 * lighting for a flat-shaded face. Planes are drawn through an exact homography, so textures and
 * shaders land where WebGL would put them.
 */

internal class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)
    fun normalized(): Vec3 = length().let { if (it == 0.0) this else this * (1 / it) }
}

/** Column-major, as three's `Matrix4.elements`: element (row r, column c) is `e[c * 4 + r]`. */
internal class Mat4(val e: DoubleArray = identity()) {
    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += e[k * 4 + row] * o.e[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    /** Multiplies (x, y, z, w). */
    fun apply(x: Double, y: Double, z: Double, w: Double) = DoubleArray(4) { row -> e[row] * x + e[4 + row] * y + e[8 + row] * z + e[12 + row] * w }

    fun point(p: Vec3) = apply(p.x, p.y, p.z, 1.0).let { Vec3(it[0], it[1], it[2]) }
    fun direction(v: Vec3) = apply(v.x, v.y, v.z, 0.0).let { Vec3(it[0], it[1], it[2]) }

    companion object {
        fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

        fun translation(x: Double, y: Double, z: Double) = Mat4().also { it.e[12] = x; it.e[13] = y; it.e[14] = z }
        fun scale(s: Double) = Mat4().also { it.e[0] = s; it.e[5] = s; it.e[10] = s }

        fun rotationX(a: Double) = Mat4().also { val c = cos(a); val s = sin(a); it.e[5] = c; it.e[6] = s; it.e[9] = -s; it.e[10] = c }
        fun rotationY(a: Double) = Mat4().also { val c = cos(a); val s = sin(a); it.e[0] = c; it.e[2] = -s; it.e[8] = s; it.e[10] = c }
        fun rotationZ(a: Double) = Mat4().also { val c = cos(a); val s = sin(a); it.e[0] = c; it.e[1] = s; it.e[4] = -s; it.e[5] = c }

        /** three's Euler in its default 'XYZ' order: Rx · Ry · Rz. */
        fun euler(x: Double, y: Double, z: Double) = rotationX(x) * rotationY(y) * rotationZ(z)

        /** three's `PerspectiveCamera.projectionMatrix` (vertical fov in degrees). */
        fun perspective(fovDeg: Double, aspect: Double, near: Double = 0.1, far: Double = 2000.0): Mat4 {
            val top = near * tan(fovDeg * PI / 360)
            val right = top * aspect
            val m = DoubleArray(16)
            m[0] = near / right
            m[5] = near / top
            m[10] = -(far + near) / (far - near)
            m[11] = -1.0
            m[14] = -2 * far * near / (far - near)
            return Mat4(m)
        }

        /** The view matrix of a camera at [eye] that has called `lookAt(0, 0, 0)` with +y up. */
        fun lookAtOrigin(eye: Vec3): Mat4 {
            val z = eye.normalized()
            val x = (Vec3(0.0, 1.0, 0.0) cross z).normalized()
            val y = z cross x
            val m = Mat4()
            m.e[0] = x.x; m.e[4] = x.y; m.e[8] = x.z
            m.e[1] = y.x; m.e[5] = y.y; m.e[9] = y.z
            m.e[2] = z.x; m.e[6] = z.y; m.e[10] = z.z
            m.e[12] = -(x dot eye); m.e[13] = -(y dot eye); m.e[14] = -(z dot eye)
            return m
        }
    }
}

/** A perspective camera at [eye] looking at the origin, rendering into a [width] × [height] px canvas. */
internal class Camera(val eye: Vec3, fovDeg: Double, val width: Float, val height: Float) {
    val view = Mat4.lookAtOrigin(eye)
    val viewProjection = Mat4.perspective(fovDeg, width.toDouble() / height) * view

    /** Screen position (px) of a world point, and its distance in front of the camera. */
    fun project(p: Vec3): Triple<Float, Float, Double> {
        val c = viewProjection.apply(p.x, p.y, p.z, 1.0)
        return Triple(((c[0] / c[3] + 1) / 2 * width).toFloat(), ((1 - c[1] / c[3]) / 2 * height).toFloat(), c[3])
    }

    fun depth(p: Vec3) = -view.point(p).z

    /**
     * The homography taking a plane's local drawing coordinates to the screen: the plane is the
     * z = 0 plane of [model], and one local world unit is [unit] drawing px, with drawing y down
     * (world y up). Concatenate it on a canvas and draw the plane flat.
     */
    fun planeMatrix(model: Mat4, unit: Double): android.graphics.Matrix {
        val o = viewProjection.apply(model.e[12], model.e[13], model.e[14], 1.0)
        val u = (viewProjection * model).apply(1.0 / unit, 0.0, 0.0, 0.0)
        val v = (viewProjection * model).apply(0.0, -1.0 / unit, 0.0, 0.0)
        val hw = width / 2.0
        val hh = height / 2.0
        // Screen x = W/2 · (cx + cw) / cw, y = H/2 · (cw − cy) / cw, each linear in (lx, ly, 1).
        val row0 = doubleArrayOf(hw * (u[0] + u[3]), hw * (v[0] + v[3]), hw * (o[0] + o[3]))
        val row1 = doubleArrayOf(hh * (u[3] - u[1]), hh * (v[3] - v[1]), hh * (o[3] - o[1]))
        val row2 = doubleArrayOf(u[3], v[3], o[3])
        val n = o[3]
        return android.graphics.Matrix().apply {
            setValues(floatArrayOf(
                (row0[0] / n).toFloat(), (row0[1] / n).toFloat(), (row0[2] / n).toFloat(),
                (row1[0] / n).toFloat(), (row1[1] / n).toFloat(), (row1[2] / n).toFloat(),
                (row2[0] / n).toFloat(), (row2[1] / n).toFloat(), (row2[2] / n).toFloat(),
            ))
        }
    }

    /** Whether the front (+z) of [model]'s plane faces the camera. */
    fun facing(model: Mat4): Boolean {
        val origin = model.point(Vec3(0.0, 0.0, 0.0))
        return (model.direction(Vec3(0.0, 0.0, 1.0)) dot (eye - origin)) > 0
    }
}

/** three's colour pipeline: sRGB in, linear maths, ACES Filmic, sRGB out. */
internal object ThreeColor {
    private val toLinear = DoubleArray(256) { i -> srgbToLinear(i / 255.0) }
    private const val ENCODE_STEPS = 4096
    private val toSrgb = IntArray(ENCODE_STEPS + 1) { i -> (linearToSrgb(i.toDouble() / ENCODE_STEPS) * 255 + 0.5).toInt().coerceIn(0, 255) }

    fun srgbToLinear(c: Double) = if (c < 0.04045) c * 0.0773993808 else ((c * 0.9478672986 + 0.0521327014).pow(2.4))
    fun linearToSrgb(c: Double) = if (c < 0.0031308) c * 12.92 else 1.055 * c.pow(0.41666) - 0.055

    fun linear(byte: Int) = toLinear[byte and 0xFF]
    fun encode(linear: Double) = toSrgb[(linear.coerceIn(0.0, 1.0) * ENCODE_STEPS).toInt()]

    private fun rrt(v: Double) = (v * (v + 0.0245786) - 0.000090537) / (v * (0.983729 * v + 0.4329510) + 0.238081)

    /** three's `ACESFilmicToneMapping` (exposure 1) on linear RGB, written into [out]. */
    fun aces(r: Double, g: Double, b: Double, out: DoubleArray) {
        val k = 1 / 0.6
        val ir = (0.59719 * r + 0.35458 * g + 0.04823 * b) * k
        val ig = (0.07600 * r + 0.90834 * g + 0.01566 * b) * k
        val ib = (0.02840 * r + 0.13383 * g + 0.83777 * b) * k
        val tr = rrt(ir)
        val tg = rrt(ig)
        val tb = rrt(ib)
        out[0] = (1.60475 * tr - 0.53108 * tg - 0.07367 * tb).coerceIn(0.0, 1.0)
        out[1] = (-0.10208 * tr + 1.10813 * tg - 0.00605 * tb).coerceIn(0.0, 1.0)
        out[2] = (-0.00327 * tr - 0.07276 * tg + 1.07602 * tb).coerceIn(0.0, 1.0)
    }

    /** How a flat `meshBasicMaterial` colour (times [light]) shows on screen. */
    fun basic(color: Color, light: Double = 1.0, alpha: Float = 1f): Color {
        val out = DoubleArray(3)
        aces(lin(color.red) * light, lin(color.green) * light, lin(color.blue) * light, out)
        return Color(encode(out[0]), encode(out[1]), encode(out[2]), (alpha * 255).toInt())
    }

    private fun lin(c: Float) = srgbToLinear(c.toDouble())

    /**
     * A flat-shaded `meshStandardMaterial` face (metalness 0) under the Turntable's lights: an
     * ambient light and one directional light, with three's Lambert diffuse and GGX specular.
     */
    fun standard(color: Color, normal: Vec3, toCamera: Vec3, roughness: Double, ambient: Double, directional: Double, lightDir: Vec3): Color {
        val n = normal.normalized()
        val v = toCamera.normalized()
        val l = lightDir.normalized()
        val nl = max(0.0, n dot l)
        val nv = max(1e-4, n dot v)
        val h = (l + v).normalized()
        val nh = max(0.0, n dot h)
        val vh = max(0.0, v dot h)
        val a2 = (roughness * roughness).let { it * it }
        val f = 0.04 + 0.96 * (1 - vh).pow(5)
        val d = a2 / (PI * (nh * nh * (a2 - 1) + 1).pow(2))
        val gv = nl * sqrt(a2 + (1 - a2) * nv * nv)
        val gl = nv * sqrt(a2 + (1 - a2) * nl * nl)
        val vis = 0.5 / max(1e-6, gv + gl)
        val irradiance = directional * nl
        val spec = irradiance * f * vis * d
        val out = DoubleArray(3)
        val diffuse = (irradiance * (1 - f) + ambient) / PI
        aces(lin(color.red) * diffuse + spec, lin(color.green) * diffuse + spec, lin(color.blue) * diffuse + spec, out)
        return Color(encode(out[0]), encode(out[1]), encode(out[2]))
    }
}
