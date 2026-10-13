package app.remastered.ui.wrapped

import app.remastered.core.Catalog
import app.remastered.core.Connection
import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import app.remastered.core.Player
import app.remastered.core.RepeatMode
import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsSource
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.Page
import app.remastered.core.model.SearchResults
import app.remastered.core.model.SearchType
import app.remastered.core.model.Track
import app.remastered.core.wrapped.WrappedFixture
import app.remastered.core.wrapped.WrappedSoundtrack
import app.remastered.core.wrapped.WrappedTimeline
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StorySoundtrackTest {
    private val data = WrappedFixture.load()
    private val timeline = WrappedTimeline.of(data)

    /** Records App Remote commands; play switches the track at once, at 0 s, playing. */
    private class FakePlayer(initial: PlaybackState?) : Player {
        override val state = MutableStateFlow(initial)
        override val connection = MutableStateFlow<Connection>(Connection.Connected)
        val calls = mutableListOf<String>()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun play(uri: String) {
            calls += "play $uri"
            state.value = playing(uri, 0, paused = false)
        }
        override fun playAt(contextUri: String, index: Int) = Unit
        override fun pause() { calls += "pause"; state.value = state.value?.copy(isPaused = true) }
        override fun resume() { calls += "resume"; state.value = state.value?.copy(isPaused = false) }
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs"; state.value = state.value?.copy(positionMs = positionMs) }
        override fun next() = Unit
        override fun previous() = Unit
        override fun setShuffle(on: Boolean) = Unit
        override fun setRepeat(mode: RepeatMode) = Unit
        override fun queue(uri: String) = Unit
        override fun setLiked(uri: String, liked: Boolean) = Unit
    }

    /** Search answers with the track the query names, 200 s long (so its hook is 60 s in). */
    private val unused = Proxy.newProxyInstance(Catalog::class.java.classLoader, arrayOf(Catalog::class.java)) { _, _, _ -> error("unused") } as Catalog
    private val catalog = object : Catalog by unused {
        override suspend fun search(query: String, types: Set<SearchType>, offset: Int, limit: Int): SearchResults {
            val name = Regex("track:\"([^\"]*)\"").find(query)?.groupValues?.get(1) ?: "Song"
            val artist = Regex("artist:\"([^\"]*)\"").find(query)?.groupValues?.get(1) ?: "Artist"
            return SearchResults(tracks = Page.of(listOf(Track(id(name), name, listOf(ArtistRef("a", artist)), null, 200_000))))
        }
    }

    private val noLyrics = object : LyricsSource {
        override suspend fun lyrics(trackUri: String) = LyricsResult.NotFound
    }

    private fun TestScope.soundtrack(player: Player, scope: CoroutineScope = this) =
        StorySoundtrack(player, catalog, noLyrics, { 10_000L }, scope, data, timeline)

    private fun firstCueUri(): String = "spotify:track:" + id(WrappedSoundtrack.cueAt(timeline, 0, data)!!.name)

    @Test fun `a slide's song starts at its hook`() = runTest {
        val player = FakePlayer(null)
        soundtrack(player).onSlide(0)
        advanceUntilIdle()
        assertEquals(listOf("play ${firstCueUri()}", "seek 60000"), player.calls)
    }

    @Test fun `a song that's already loaded is seeked, not restarted`() = runTest {
        // Unmuting on a slide whose song is still loaded: playing it again would land at 0 s.
        val player = FakePlayer(playing(firstCueUri(), 150_000, paused = true))
        soundtrack(player).onSlide(0)
        advanceUntilIdle()
        assertEquals(listOf("seek 60000", "resume"), player.calls)
    }

    @Test fun `closing brings back a paused track, paused where it was`() = runTest {
        val player = FakePlayer(playing("spotify:track:mine", 16_000, paused = true))
        val soundtrack = soundtrack(player)
        soundtrack.onSlide(0)
        advanceUntilIdle()
        player.calls.clear()
        soundtrack.restore()
        advanceUntilIdle()
        assertEquals(listOf("play spotify:track:mine", "pause", "seek 16000"), player.calls)
    }

    @Test fun `closing brings back a playing track where it would be now`() = runTest {
        // Playing from 16 s at clock 4 s; the story closes at clock 10 s.
        val player = FakePlayer(playing("spotify:track:mine", 16_000, paused = false, at = 4_000))
        val soundtrack = soundtrack(player)
        soundtrack.onSlide(0)
        advanceUntilIdle()
        player.calls.clear()
        soundtrack.restore()
        advanceUntilIdle()
        assertEquals(listOf("play spotify:track:mine", "seek 22000"), player.calls)
    }

    private companion object {
        fun id(name: String) = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "x" }

        fun playing(uri: String, positionMs: Long, paused: Boolean, at: Long = 0) = PlaybackState(
            NowPlaying(uri, uri, emptyList(), "", null, null, 300_000), paused, positionMs, at,
        )
    }
}
