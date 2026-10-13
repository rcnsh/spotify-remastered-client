package app.remastered

import app.remastered.spotify.auth.SpotifyAuth
import app.remastered.spotify.auth.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private fun tokens(scope: String, expiresAt: Long = 0) = Tokens("access-secret", "refresh-secret", expiresAt, scope)

    @Test fun missingScopesListsRequestedButNotGranted() {
        val granted = SpotifyAuth.SCOPES.filterNot { it == "streaming" || it == "user-top-read" }
        assertEquals(listOf("user-top-read", "streaming"), Diagnostics.missingScopes(tokens(granted.joinToString(" "))))
        assertTrue(Diagnostics.missingScopes(tokens(SpotifyAuth.SCOPES.joinToString(" "))).isEmpty())
    }

    @Test fun expiryDescribesForcedPastAndFuture() {
        val now = 1_000_000L
        assertEquals("expired (forced)", Diagnostics.expiry(0, now))
        assertTrue(Diagnostics.expiry(now - 120_000, now).startsWith("expired 2 min ago"))
        assertTrue(Diagnostics.expiry(now + 125_000, now).startsWith("valid for 2 min 5 s"))
    }

    @Test fun sessionNeverContainsTokens() {
        val rows = Diagnostics.session(tokens("user-read-private", 5_000_000), now = 1_000_000)
        val text = rows.joinToString { "${it.label} ${it.value}" }
        assertFalse(text.contains("access-secret"))
        assertFalse(text.contains("refresh-secret"))
        assertTrue(rows.any { it.label == "Missing" })
    }
}
