package app.remastered.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverJpegTest {
    @Test fun base64Size() {
        assertEquals(0, CoverJpeg.base64Size(0))
        assertEquals(4, CoverJpeg.base64Size(1))
        assertEquals(4, CoverJpeg.base64Size(3))
        assertEquals(8, CoverJpeg.base64Size(4))
    }

    @Test fun picksTheBestQualityThatFits() {
        val tried = mutableListOf<Int>()
        // 100 bytes of base64 holds 75 bytes; pretend size grows with quality.
        val jpeg = CoverJpeg.fit(maxBase64 = 100, qualities = listOf(90, 70, 50)) { q -> tried += q; ByteArray(q) }
        assertArrayEquals(ByteArray(70), jpeg)
        assertEquals(listOf(90, 70), tried)
    }

    @Test fun nullWhenNothingFits() {
        assertNull(CoverJpeg.fit(maxBase64 = 10, qualities = listOf(50, 30)) { ByteArray(100) })
    }
}
