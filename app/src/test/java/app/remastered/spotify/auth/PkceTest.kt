package app.remastered.spotify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun `RFC 7636 appendix B vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun `verifier is long enough and unreserved`() {
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
        assertNotEquals(v, Pkce.verifier())
    }
}
