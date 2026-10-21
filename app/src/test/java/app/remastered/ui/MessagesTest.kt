package app.remastered.ui

import app.remastered.core.CatalogException
import app.remastered.core.warehouse.WarehouseException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the user is told when a Web API or warehouse call fails. */
class MessagesTest {
    private fun spotify(status: Int?, retryAfterMs: Long? = null) = CatalogException("Spotify $status", status, retryAfterMs = retryAfterMs)

    @Test fun eachFailureHasItsOwnMessage() {
        assertEquals("Sign in to Spotify again.", AppState.describe(spotify(401)))
        assertEquals("Spotify doesn't allow that for this app.", AppState.describe(spotify(403)))
        assertEquals("Can't reach Spotify. Check your connection.", AppState.describe(spotify(null)))
        assertEquals("Something went wrong.", AppState.describe(spotify(500)))
        assertEquals("Something went wrong.", AppState.describe(IOException("boom")))
    }

    @Test fun aShortRateLimitSaysHowLongToWait() {
        assertEquals("Spotify is limiting requests. Try again in 30 s.", AppState.describe(spotify(429, 30_000)))
        assertEquals("Spotify is limiting requests. Try again in a minute.", AppState.describe(spotify(429)))
        assertEquals("Spotify is limiting requests.", AppState.describe(spotify(429, 30_000), withWait = false))
    }

    @Test fun aLongRateLimitIsSpotifysQuota() {
        val m = AppState.describe(spotify(429, AppState.LONG_PAUSE_MS))
        assertTrue(m, m.startsWith("Spotify has paused this app's requests"))
        assertTrue(m, m.endsWith("Try again in 10 min."))
        assertEquals("Spotify is limiting requests. Try again in 10 min.", AppState.describe(spotify(429, AppState.LONG_PAUSE_MS - 1_000)))
    }

    @Test fun warehouseFailuresUseTheirOwnMessage() {
        val e = WarehouseException(WarehouseException.Problem.Unauthorized, "401", status = 401)
        assertEquals(e.userMessage, AppState.describe(e))
    }

    @Test fun aRateLimitedLoadKnowsWhenToRetry() {
        val before = System.currentTimeMillis()
        val f = Load.Failed(spotify(429, 45_000))
        assertEquals("Spotify is limiting requests.", f.message)
        val at = f.retryAt!!
        assertTrue(at in before + 45_000..System.currentTimeMillis() + 45_000)
    }

    @Test fun aRateLimitWithoutRetryAfterWaitsAMinute() {
        val before = System.currentTimeMillis()
        val at = Load.Failed(spotify(429)).retryAt!!
        assertTrue(at in before + 60_000..System.currentTimeMillis() + 60_000)
    }

    @Test fun otherFailuresHaveNoRetryTime() {
        assertNull(Load.Failed(spotify(500)).retryAt)
        assertNull(Load.Failed(IOException("offline")).retryAt)
    }
}
