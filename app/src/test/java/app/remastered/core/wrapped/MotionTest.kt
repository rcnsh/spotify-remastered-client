package app.remastered.core.wrapped

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values come from Remotion 4.0.535 itself (node, in music-warehouse-wrapped). */
class MotionTest {
    @Test fun `seeded random matches Remotion`() {
        val expected = mapOf(
            "intro x0" to 0.9278270297218114, "grain0" to 0.655799695989117, "grain-a5" to 0.39928944199346006,
            "minutesx3" to 0.15377019671723247, "tape" to 0.4773861262947321, "gx7" to 0.6288110804744065,
        )
        expected.forEach { (seed, value) -> assertEquals(seed, value, random(seed), 1e-15) }
    }

    @Test fun `springs match Remotion frame for frame`() {
        val bouncy = listOf(0.0, 1.0218087021903397, 1.1097842572518544, 0.9677754343598551, 0.9983611006473901, 0.9999973170045012)
        listOf(0.0, 5.0, 10.0, 16.5, 30.0, 60.0).zip(bouncy).forEach { (f, v) -> assertEquals("bouncy@$f", v, spring(f, 30, Springs.bouncy), 1e-12) }
        val snappy = listOf(0.0, 0.9660740194743185, 1.0256479511201193, 0.9999669108684437)
        listOf(0.0, 5.0, 10.0, 20.0).zip(snappy).forEach { (f, v) -> assertEquals("snappy@$f", v, spring(f, 30, Springs.snappy), 1e-12) }
        assertEquals(0.0, spring(-12.0, 30, Springs.soft), 0.0)
    }

    @Test fun `interpolate and easing match Remotion`() {
        val back = Easing.out(Easing.back(1.6))
        listOf(3.0 to 0.4832031250000002, 10.0 to 1.0283564814814816, 20.0 to 1.0324074074074074).forEach { (f, v) ->
            assertEquals(v, interpolateClamped(f, 0.0, 24.0, easing = back), 1e-12)
        }
        // Extends past the range by default; clamps when asked.
        assertEquals(15.0, interpolate(30.0, doubleArrayOf(0.0, 20.0), doubleArrayOf(0.0, 10.0)), 0.0)
        assertEquals(10.0, interpolateClamped(30.0, 0.0, 20.0, 0.0, 10.0), 0.0)
        assertEquals(0.25, interpolate(5.0, doubleArrayOf(0.0, 20.0, 40.0), doubleArrayOf(0.0, 1.0, 0.0)), 0.0)
        assertEquals(0.5, interpolate(30.0, doubleArrayOf(0.0, 20.0, 40.0), doubleArrayOf(0.0, 1.0, 0.0)), 0.0)
        assertEquals(0.5, Easing.inOut(Easing.cubic)(0.5), 1e-12)
    }
}
