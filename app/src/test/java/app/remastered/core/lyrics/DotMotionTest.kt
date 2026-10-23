package app.remastered.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotMotionTest {
    @Test fun `a spline passes through its points and holds past the ends`() {
        val s = Spline(floatArrayOf(0f, .5f, 1f), floatArrayOf(0f, 2f, 1f))
        assertEquals(0f, s.at(0f), 1e-5f)
        assertEquals(2f, s.at(.5f), 1e-5f)
        assertEquals(1f, s.at(1f), 1e-5f)
        assertEquals(0f, s.at(-1f), 1e-5f)
        assertEquals(1f, s.at(2f), 1e-5f)
    }

    @Test fun `a dot rests small and dim, rises mid-turn, and ends lit and level`() {
        assertEquals(DotLook(.75f, 0f, 0f), DotMotion.look(0f))
        assertEquals(DotLook(1f, 0f, 1f), DotMotion.look(1f))
        assertEquals(.27f, DotMotion.look(.9f).lift, 1e-5f)
        assertEquals(.63f, DotMotion.look(.6f).lift, .01f)
        assertTrue(DotMotion.look(.6f).lift > DotMotion.look(.3f).lift)
    }
}
