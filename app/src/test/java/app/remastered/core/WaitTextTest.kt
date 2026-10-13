package app.remastered.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitTextTest {
    @Test fun roundsUp() {
        assertEquals("1 s", waitText(1))
        assertEquals("45 s", waitText(44_200))
        assertEquals("1 min", waitText(60_000))
        assertEquals("2 min", waitText(61_000))
        assertEquals("1 hr", waitText(3_600_000))
        assertEquals("8 hr 31 min", waitText(30_630_000))
    }
}
