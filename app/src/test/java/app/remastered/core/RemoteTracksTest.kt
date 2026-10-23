package app.remastered.core

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTracksTest {
    /** Serves [all] in pages, like App Remote; records the offsets asked for. */
    private class FakeContent(val all: List<ContentItem>, val fail: Boolean = false, val connected: Boolean = true) : RemoteContent {
        val offsets = mutableListOf<Int>()
        override suspend fun children(uri: String, limit: Int, offset: Int): ContentPage? {
            offsets += offset
            if (!connected) return null
            if (fail) throw IOException("nope")
            return ContentPage(all.size, all.drop(offset).take(limit))
        }
    }

    private fun track(n: Int) = ContentItem("spotify:track:t$n", "Song $n", "Artist $n, Other", "appremote:img$n", playable = true)

    @Test fun readsEveryPageWithPositions() = runBlocking {
        val content = FakeContent((0 until 7).map(::track))
        val items = RemoteTracks.load(content, "spotify:playlist:x", pageSize = 3)!!
        assertEquals(listOf(0, 3, 6), content.offsets)
        assertEquals((0 until 7).toList(), items.map { it.position })
        val first = items.first().track
        assertEquals("spotify:track:t0", first.uri)
        assertEquals("Song 0", first.name)
        assertEquals("Artist 0, Other", first.artistNames)
        assertEquals("appremote:img0", first.album?.images?.single()?.url)
    }

    @Test fun skipsWhatIsNotATrackButKeepsPositions() = runBlocking {
        val items = listOf(
            track(0),
            ContentItem("spotify:local:a:b:c:1", "Local", "", null, playable = true),
            ContentItem(null, "Nothing", "", null, playable = false),
            ContentItem("spotify:episode:e1", "Episode", "Show", null, playable = true),
        )
        val loaded = RemoteTracks.load(FakeContent(items), "spotify:playlist:x")!!
        assertEquals(listOf("spotify:track:t0" to 0, "spotify:episode:e1" to 3), loaded.map { it.track.uri to it.position })
        assertEquals(null, loaded[1].track.album)
    }

    @Test fun stopsAtMax() = runBlocking {
        val content = FakeContent((0 until 10).map(::track))
        assertEquals(4, RemoteTracks.load(content, "spotify:playlist:x", pageSize = 2, max = 4)!!.size)
    }

    @Test fun nullWhenNotConnectedOrFailing() = runBlocking {
        assertNull(RemoteTracks.load(FakeContent(listOf(track(0)), connected = false), "spotify:playlist:x"))
        assertNull(RemoteTracks.load(FakeContent(listOf(track(0)), fail = true), "spotify:playlist:x"))
    }

    @Test fun emptyPlaylistIsEmptyNotNull() = runBlocking {
        assertEquals(emptyList<Any>(), RemoteTracks.load(FakeContent(emptyList()), "spotify:playlist:x"))
    }
}
