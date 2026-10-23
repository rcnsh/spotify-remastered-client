package app.remastered.core

import app.remastered.core.model.Playlist
import app.remastered.core.model.UserRef
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavePlaylistTest {
    private fun id(n: Int) = "%022d".format(n)
    private fun uri(n: Int) = "spotify:track:${id(n)}"

    /** Records the playlist writes; [failCreate] / [failAdd] make that call fail like Spotify would. */
    private class FakeSpotify(val failCreate: Boolean = false, val failAdd: Boolean = false) {
        val created = mutableListOf<Pair<String, String>>()
        val added = mutableListOf<Pair<String, List<String>>>()
        private val unused = Proxy.newProxyInstance(Catalog::class.java.classLoader, arrayOf(Catalog::class.java)) { _, _, _ -> error("unused") } as Catalog
        val catalog = object : Catalog by unused {
            override suspend fun createPlaylist(name: String, description: String): Playlist {
                if (failCreate) throw CatalogException("Spotify 403: Forbidden", 403)
                created += name to description
                return Playlist("p1", name, description, emptyList(), UserRef("me", "Me"), 0, false, "s0")
            }

            override suspend fun addToPlaylist(playlistId: String, uris: List<String>, position: Int?): String {
                if (failAdd) throw CatalogException("Spotify 429: rate limited", 429, retryAfterMs = 60_000)
                added += playlistId to uris
                return "s1"
            }
        }
    }

    @Test fun `creates one playlist and adds every song in one request`() = runTest {
        val spotify = FakeSpotify()
        val saved = SavePlaylist.save(spotify.catalog, "  On repeat · 23 Oct 2026 ", (1..50).map(::uri))
        assertEquals(listOf("On repeat · 23 Oct 2026" to SavePlaylist.DESCRIPTION), spotify.created)
        assertEquals(listOf("p1" to (1..50).map(::uri)), spotify.added)
        assertEquals(50, saved.added)
        assertEquals(0, saved.skipped)
        assertEquals(50, saved.playlist.trackCount)
    }

    @Test fun `songs without a Spotify id and repeats are left out`() = runTest {
        val spotify = FakeSpotify()
        val saved = SavePlaylist.save(spotify.catalog, "Top songs", listOf(uri(1), null, "spotify:track:name:b", uri(1), uri(2), "spotify:episode:${id(3)}"))
        assertEquals(listOf(uri(1), uri(2)), spotify.added.single().second)
        assertEquals(2, saved.added)
        assertEquals(4, saved.skipped)
    }

    @Test fun `long lists stop at one request's worth`() {
        assertEquals(SavePlaylist.MAX_SONGS, SavePlaylist.uris((1..250).map(::uri)).size)
        assertEquals(uri(1), SavePlaylist.uris((1..250).map(::uri)).first())
    }

    @Test fun `nothing to save sends nothing`() = runTest {
        val spotify = FakeSpotify()
        try {
            SavePlaylist.save(spotify.catalog, "Top songs", listOf(null, "spotify:track:name:x"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(spotify.created.isEmpty())
    }

    @Test fun `a failed create says nothing was made`() = runTest {
        val spotify = FakeSpotify(failCreate = true)
        val e = failure { SavePlaylist.save(spotify.catalog, "Top songs", listOf(uri(1))) }
        assertNull(e.created)
        assertEquals(403, e.cause.status)
        assertTrue(spotify.added.isEmpty())
    }

    @Test fun `a failed add keeps the playlist it made`() = runTest {
        val spotify = FakeSpotify(failAdd = true)
        val e = failure { SavePlaylist.save(spotify.catalog, "Top songs", listOf(uri(1))) }
        assertEquals("p1", e.created?.id)
        assertTrue(e.cause.isRateLimited)
    }

    private suspend fun failure(block: suspend () -> Unit): SavePlaylist.Failed {
        try {
            block()
        } catch (e: SavePlaylist.Failed) {
            return e
        }
        fail("expected SavePlaylist.Failed")
        error("unreachable")
    }
}
