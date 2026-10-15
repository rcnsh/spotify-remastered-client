package app.remastered.spotify.auth

import app.remastered.core.Sealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedValueTest {
    /** Reverses the text: enough to tell sealed from plain without the Android Keystore. */
    private class FakeSealer(private val opens: Boolean = true) : Sealer {
        override fun seal(plain: String) = plain.reversed()
        override fun open(sealed: String) = if (opens) sealed.reversed() else null
    }

    @Test fun `a token is written sealed and reads back`() {
        val stored = SealedValue.write("BQD-access_token", FakeSealer())
        assertEquals("v1:nekot_ssecca-DQB", stored)
        val read = SealedValue.read(stored, FakeSealer())
        assertEquals("BQD-access_token", read.value)
        assertFalse(read.inClear)
    }

    @Test fun `a token saved in the clear by an older version still reads, marked for sealing`() {
        val read = SealedValue.read("AQC-refresh_token", FakeSealer())
        assertEquals("AQC-refresh_token", read.value)
        assertTrue(read.inClear)
    }

    @Test fun `a sealed token whose key is gone reads as missing`() {
        val read = SealedValue.read(SealedValue.write("BQD", FakeSealer()), FakeSealer(opens = false))
        assertNull(read.value)
        assertFalse(read.inClear)
    }
}
