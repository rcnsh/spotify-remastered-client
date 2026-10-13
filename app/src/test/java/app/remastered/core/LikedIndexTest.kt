package app.remastered.core

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.Image
import app.remastered.core.model.Page
import app.remastered.core.model.Track
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LikedIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun track(n: Int) = Track("t$n", "Song $n", listOf(ArtistRef("a1", "Artist")), AlbumRef("al1", "Album", listOf(Image("https://i/1", 64, 64))), 180_000)

    /** Spotify's Liked Songs, newest first; counts every page request. */
    private class FakeLibrary(var liked: List<Track>) {
        val offsets = mutableListOf<Int>()
        var failing = false
        private val unused = Proxy.newProxyInstance(Catalog::class.java.classLoader, arrayOf(Catalog::class.java)) { _, _, _ -> error("unused") } as Catalog
        val catalog = object : Catalog by unused {
            override suspend fun likedTracks(offset: Int, limit: Int): Page<Track> {
                offsets += offset
                if (failing) throw CatalogException("Spotify 429: rate limited", 429, retryAfterMs = 60_000)
                val items = liked.drop(offset).take(limit)
                return Page(items, offset, liked.size, offset + items.size < liked.size)
            }
        }
    }

    private class MemoryStore(var saved: List<Track>? = null) : LikedIndex.Store {
        override fun load() = saved
        override fun save(tracks: List<Track>) { saved = tracks }
        override fun clear() { saved = null }
    }

    private fun TestScope.index(library: FakeLibrary, store: LikedIndex.Store) =
        LikedIndex(library.catalog, this, store, io = StandardTestDispatcher(testScheduler))

    @Test fun firstStartLoadsEverythingAndSavesIt() = runTest {
        val library = FakeLibrary((1..120).map(::track))
        val store = MemoryStore()
        val index = index(library, store)
        index.load()
        advanceUntilIdle()
        assertEquals(listOf(0, 50, 100), library.offsets)
        assertTrue(index.complete.value)
        assertEquals(120, store.saved?.size)
    }

    @Test fun laterStartFetchesOnlyWhatsNew() = runTest {
        val old = (1..120).map(::track)
        val library = FakeLibrary(listOf(track(200), track(201)) + old)
        val index = index(library, MemoryStore(old))
        index.load()
        advanceUntilIdle()
        assertEquals(listOf(0), library.offsets)
        assertEquals(122, index.tracks.value.size)
        assertEquals("t200", index.tracks.value.first().id)
        assertTrue(index.complete.value)
    }

    @Test fun newSongsSpanningPagesAreFollowed() = runTest {
        val old = (1..10).map(::track)
        val library = FakeLibrary((200..260).map(::track) + old)
        val index = index(library, MemoryStore(old))
        index.load()
        advanceUntilIdle()
        assertEquals(listOf(0, 50), library.offsets)
        assertEquals(71, index.tracks.value.size)
    }

    @Test fun unlikedElsewhereLoadsEverythingAgain() = runTest {
        val old = (1..120).map(::track)
        val library = FakeLibrary(old.filterNot { it.id == "t90" })
        val index = index(library, MemoryStore(old))
        index.load()
        advanceUntilIdle()
        // One catch-up page (total doesn't add up), then the full load.
        assertEquals(listOf(0, 0, 50, 100), library.offsets)
        assertEquals(119, index.tracks.value.size)
        assertFalse(index.tracks.value.any { it.id == "t90" })
    }

    @Test fun relikedSongAtTheTopLoadsEverythingAgain() = runTest {
        val old = (1..5).map(::track)
        val library = FakeLibrary(listOf(track(3)) + old.filterNot { it.id == "t3" })
        val index = index(library, MemoryStore(old))
        index.load()
        advanceUntilIdle()
        assertEquals(listOf(0, 0), library.offsets)
        assertEquals(listOf("t3", "t1", "t2", "t4", "t5"), index.tracks.value.map { it.id })
    }

    @Test fun savedListStaysShownWhenSpotifyFails() = runTest {
        val old = (1..3).map(::track)
        val library = FakeLibrary(old).apply { failing = true }
        val index = index(library, MemoryStore(old))
        index.load()
        advanceUntilIdle()
        assertEquals(3, index.tracks.value.size)
        assertFalse(index.complete.value)
        assertEquals(listOf(0), library.offsets)
    }

    @Test fun clearForgetsTheSavedCopy() = runTest {
        val store = MemoryStore((1..3).map(::track))
        val index = index(FakeLibrary(emptyList()), store)
        index.clear()
        advanceUntilIdle()
        assertNull(store.saved)
        assertTrue(index.tracks.value.isEmpty())
    }

    @Test fun fileRoundTrips() {
        val file = LikedSongsFile(tmp.root.resolve("liked.json"))
        assertNull(file.load())
        val tracks = listOf(track(1), track(2).copy(explicit = true, isEpisode = true))
        file.save(tracks)
        assertEquals(tracks, file.load())
        file.clear()
        assertNull(file.load())
    }

    @Test fun unreadableFileCountsAsNothingSaved() {
        val f = tmp.root.resolve("liked.json").apply { writeText("{not json") }
        assertNull(LikedSongsFile(f).load())
    }
}
