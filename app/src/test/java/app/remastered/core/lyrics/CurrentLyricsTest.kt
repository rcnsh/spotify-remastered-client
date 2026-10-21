package app.remastered.core.lyrics

import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import app.remastered.core.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLyricsTest {
    private val playing = MutableStateFlow<PlaybackState?>(null)
    private val enabled = MutableStateFlow(true)
    private val unused = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> error("unused") } as Player
    private val player = object : Player by unused {
        override val state = playing
    }

    /** Answers every song with [result]; a song in [gates] waits until its gate completes. */
    private class FakeSource : LyricsSource {
        val asked = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        override suspend fun lyrics(trackUri: String): LyricsResult {
            asked += trackUri
            gates[trackUri]?.await()
            return LyricsResult.Found(lyricsFor(trackUri))
        }
    }

    private val source = FakeSource()

    private fun play(n: Int, positionMs: Long = 0) {
        playing.value = PlaybackState(
            NowPlaying("spotify:track:$n", "Song $n", emptyList(), "Album", null, null, durationMs = 200_000),
            isPaused = false, positionMs = positionMs, positionAtMs = 0,
        )
    }

    /** Lyrics for the song, observed from the start like an open Now Playing screen. */
    private fun TestScope.observe(): List<LyricsState> {
        val lyrics = CurrentLyrics(player, source, enabled, backgroundScope)
        val seen = mutableListOf<LyricsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lyrics.state.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test fun idleWhileNothingPlays() = runTest {
        val seen = observe()
        assertEquals(listOf(LyricsState.Idle), seen)
        assertEquals(emptyList<String>(), source.asked)
    }

    @Test fun loadsThenShowsTheSongsLyrics() = runTest {
        play(1)
        val seen = observe()
        assertEquals(
            listOf(LyricsState.Idle, LyricsState.Loading("spotify:track:1"), LyricsState.Ready("spotify:track:1", found(1))),
            seen,
        )
    }

    @Test fun progressInTheSameSongDoesntFetchAgain() = runTest {
        play(1)
        observe()
        play(1, positionMs = 10_000)
        play(1, positionMs = 20_000)
        runCurrent()
        assertEquals(listOf("spotify:track:1"), source.asked)
    }

    @Test fun aNewSongReplacesASlowFetch() = runTest {
        source.gates["spotify:track:1"] = CompletableDeferred()
        play(1)
        val seen = observe()
        play(2)
        runCurrent()
        source.gates.getValue("spotify:track:1").complete(Unit)
        runCurrent()
        assertEquals(LyricsState.Ready("spotify:track:2", found(2)), seen.last())
        // The first song's answer never shows.
        assertEquals(emptyList<LyricsState>(), seen.filter { it is LyricsState.Ready && it.trackUri == "spotify:track:1" })
    }

    @Test fun switchedOffShowsOffAndFetchesNothing() = runTest {
        enabled.value = false
        play(1)
        val seen = observe()
        assertEquals(LyricsState.Off, seen.last())
        assertEquals(emptyList<String>(), source.asked)
        enabled.value = true
        runCurrent()
        assertEquals(LyricsState.Ready("spotify:track:1", found(1)), seen.last())
    }

    @Test fun retryFetchesTheSameSongAgain() = runTest {
        play(1)
        val lyrics = CurrentLyrics(player, source, enabled, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lyrics.state.collect {} }
        runCurrent()
        lyrics.retry()
        runCurrent()
        assertEquals(listOf("spotify:track:1", "spotify:track:1"), source.asked)
    }

    @Test fun nothingIsFetchedWithoutAnObserver() = runTest {
        play(1)
        CurrentLyrics(player, source, enabled, backgroundScope)
        play(2)
        runCurrent()
        assertEquals(emptyList<String>(), source.asked)
    }

    private companion object {
        fun lyricsFor(uri: String) = Lyrics(
            uri.substringAfterLast(':'), Lyrics.Sync.Static,
            listOf(LyricLine(0, 1_000, Vocal(0, 1_000, listOf(Syllable("la", 0, 1_000))))),
            emptyList(), Attribution(Provider.SpicyLyrics),
        )

        fun found(n: Int) = LyricsResult.Found(lyricsFor("spotify:track:$n"))
    }
}
