package app.remastered.core.wrapped

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Remotion's animation maths, ported line for line so every value on a frame matches the web
 * story (music-warehouse-wrapped). Nothing here reads a clock: a value is a function of the frame.
 */

/** Remotion's `Easing`: functions from progress 0..1 to eased progress. */
object Easing {
    val linear: (Double) -> Double = { it }
    val cubic: (Double) -> Double = { it * it * it }
    fun back(s: Double = 1.70158): (Double) -> Double = { t -> t * t * ((s + 1) * t - s) }
    fun out(easing: (Double) -> Double): (Double) -> Double = { t -> 1 - easing(1 - t) }
    fun inOut(easing: (Double) -> Double): (Double) -> Double = { t -> if (t < 0.5) easing(t * 2) / 2 else 1 - easing((1 - t) * 2) / 2 }
}

enum class Extrapolate { Extend, Clamp, Identity }

/** Remotion's `interpolate`: maps [input] across matching ranges, easing each segment. */
fun interpolate(
    input: Double,
    inputRange: DoubleArray,
    outputRange: DoubleArray,
    easing: (Double) -> Double = Easing.linear,
    left: Extrapolate = Extrapolate.Extend,
    right: Extrapolate = Extrapolate.Extend,
): Double {
    require(inputRange.size == outputRange.size && inputRange.size >= 2)
    var i = 1
    while (i < inputRange.size - 1) {
        if (inputRange[i] >= input) break
        i++
    }
    val range = i - 1
    val inMin = inputRange[range]
    val inMax = inputRange[range + 1]
    val outMin = outputRange[range]
    val outMax = outputRange[range + 1]
    var result = input
    if (result < inMin) {
        when (left) {
            Extrapolate.Identity -> return result
            Extrapolate.Clamp -> result = inMin
            Extrapolate.Extend -> Unit
        }
    }
    if (result > inMax) {
        when (right) {
            Extrapolate.Identity -> return result
            Extrapolate.Clamp -> result = inMax
            Extrapolate.Extend -> Unit
        }
    }
    if (outMin == outMax) return outMin
    result = (result - inMin) / (inMax - inMin)
    result = easing(result)
    return result * (outMax - outMin) + outMin
}

/** Shorthand for the common clamped two-point case. */
fun interpolateClamped(input: Double, from: Double, to: Double, outFrom: Double = 0.0, outTo: Double = 1.0, easing: (Double) -> Double = Easing.linear): Double =
    interpolate(input, doubleArrayOf(from, to), doubleArrayOf(outFrom, outTo), easing, Extrapolate.Clamp, Extrapolate.Clamp)

data class SpringConfig(val damping: Double = 10.0, val mass: Double = 1.0, val stiffness: Double = 100.0)

/** The springs the web story uses (theme.ts SPRING), so motion has one personality. */
object Springs {
    val snappy = SpringConfig(damping = 14.0, stiffness = 180.0, mass = 0.6)
    val bouncy = SpringConfig(damping = 9.0, stiffness = 140.0, mass = 0.7)
    val soft = SpringConfig(damping = 22.0, stiffness = 90.0, mass = 1.0)
}

private class SpringNode(val lastTimestamp: Double, val current: Double, val velocity: Double)

private fun advance(node: SpringNode, now: Double, c: SpringConfig): SpringNode {
    val deltaTime = min(now - node.lastTimestamp, 64.0)
    val toValue = 1.0
    val v0 = -node.velocity
    val x0 = toValue - node.current
    val zeta = c.damping / (2 * sqrt(c.stiffness * c.mass))
    val omega0 = sqrt(c.stiffness / c.mass)
    val omega1 = omega0 * sqrt(1 - zeta * zeta)
    val t = deltaTime / 1000
    val sin1 = sin(omega1 * t)
    val cos1 = cos(omega1 * t)
    val underEnvelope = exp(-zeta * omega0 * t)
    val underFrag = underEnvelope * (sin1 * ((v0 + zeta * omega0 * x0) / omega1) + x0 * cos1)
    val underPosition = toValue - underFrag
    val underVelocity = zeta * omega0 * underFrag - underEnvelope * (cos1 * (v0 + zeta * omega0 * x0) - omega1 * x0 * sin1)
    val critEnvelope = exp(-omega0 * t)
    val critPosition = toValue - critEnvelope * (x0 + (v0 + omega0 * x0) * t)
    val critVelocity = critEnvelope * (v0 * (t * omega0 - 1) + t * x0 * omega0 * omega0)
    return if (zeta < 1) SpringNode(now, underPosition, underVelocity) else SpringNode(now, critPosition, critVelocity)
}

/** Per config and fps, the spring's state at each whole frame, grown as frames are asked for. */
private val springTables = HashMap<Pair<SpringConfig, Int>, ArrayList<SpringNode>>()

/**
 * Remotion's `spring()` from 0 to 1 at [frame] (fractional frames allowed), stepping the physics
 * once per frame exactly as Remotion does so the overshoot lands on the same frames.
 */
fun spring(frame: Double, fps: Int = WrappedTimeline.FPS, config: SpringConfig = SpringConfig()): Double {
    val clamped = max(0.0, frame)
    val whole = floor(clamped).toInt()
    val node = synchronized(springTables) {
        val table = springTables.getOrPut(config to fps) { arrayListOf(advance(SpringNode(0.0, 0.0, 0.0), 0.0, config)) }
        while (table.size <= whole) table.add(advance(table.last(), table.size * 1000.0 / fps, config))
        table[whole]
    }
    val rest = clamped % 1
    return if (rest > 0) advance(node, clamped / fps * 1000, config).current else node.current
}

/**
 * Remotion's seeded `random(string)`: a 32-bit string hash fed to mulberry32. Same seed, same
 * number, on every frame and every device.
 */
fun random(seed: String): Double {
    var hash = 0
    for (ch in seed) hash = (hash shl 5) - hash + ch.code
    var t = hash + 0x6d2b79f5
    t = (t xor (t ushr 15)) * (t or 1)
    t = t xor (t + (t xor (t ushr 7)) * (t or 61))
    return (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
}

/** True when [a] and [b] differ by less than [eps]; for tests and guards. */
internal fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps
