package app.remastered.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class OptimisticPlaybackTest {
    private var now = 10_000L
    private val playback = OptimisticPlayback({ now })
    private val shown get() = playback.state.value!!

    private fun track(n: Int, liked: Boolean = false) =
        NowPlaying("spotify:track:$n", "Song $n", emptyList(), "Album", null, null, durationMs = 200_000, liked = liked)

    /** What Spotify reports: [position] as of now. */
    private fun remote(
        position: Long = 30_000,
        paused: Boolean = false,
        track: NowPlaying? = track(1),
        shuffle: Boolean = false,
        repeat: RepeatMode = RepeatMode.Off,
    ) = PlaybackState(track, paused, position, positionAtMs = now, shuffle = shuffle, repeat = repeat)

    @Test fun nothingShownUntilSpotifyReports() {
        assertNull(playback.state.value)
        playback.onRemote(remote())
        assertEquals(remote(), shown)
    }

    @Test fun pauseShowsAtOnceAndFreezesThePosition() {
        playback.onRemote(remote(position = 30_000))
        now += 1_000
        playback.setPaused(true)
        assertTrue(shown.isPaused)
        now += 1_000
        assertEquals(31_000, shown.positionAt(now))
    }

    @Test fun spotifyConfirmingThePauseTakesOver() {
        playback.onRemote(remote(position = 30_000))
        now += 1_000
        playback.setPaused(true)
        now += 200
        playback.onRemote(remote(position = 31_150, paused = true))
        assertTrue(shown.isPaused)
        // Spotify's own position, not the frozen guess.
        assertEquals(31_150, shown.positionMs)
        // And it holds after the override would have expired.
        now += 10_000
        playback.tick()
        assertTrue(shown.isPaused)
    }

    @Test fun aReportFromBeforeTheCommandDoesntUndoIt() {
        playback.onRemote(remote())
        playback.setShuffle(true)
        playback.setRepeat(RepeatMode.One)
        now += 100
        playback.onRemote(remote(shuffle = false, repeat = RepeatMode.Off))
        assertTrue(shown.shuffle)
        assertEquals(RepeatMode.One, shown.repeat)
    }

    @Test fun aRefusedCommandSettlesBackAfterTheHold() {
        playback.onRemote(remote())
        playback.setShuffle(true)
        now += 2_499
        playback.tick()
        assertTrue(shown.shuffle)
        now += 1
        playback.tick()
        assertFalse(shown.shuffle)
    }

    @Test fun seekShowsAtOnceAndKeepsPlaying() {
        playback.onRemote(remote(position = 30_000))
        playback.seekTo(100_000)
        now += 1_000
        assertEquals(101_000, shown.positionAt(now))
        // Spotify hasn't caught up yet: its old position doesn't pull the bar back.
        playback.onRemote(remote(position = 31_000))
        assertEquals(101_000, shown.positionAt(now))
    }

    @Test fun spotifyReachingTheSeekTakesOver() {
        playback.onRemote(remote(position = 30_000))
        playback.seekTo(100_000)
        now += 500
        playback.onRemote(remote(position = 100_900))
        assertEquals(100_900, shown.positionMs)
        assertEquals(now, shown.positionAtMs)
    }

    @Test fun seekBeforeTheStartIsTheStart() {
        playback.onRemote(remote())
        playback.seekTo(-5_000)
        assertEquals(0, shown.positionMs)
    }

    @Test fun aNewTrackDropsTheOldTracksSeek() {
        playback.onRemote(remote(position = 30_000))
        playback.seekTo(150_000)
        now += 100
        playback.onRemote(remote(position = 0, track = track(2)))
        assertEquals("spotify:track:2", shown.track?.uri)
        assertEquals(0, shown.positionMs)
    }

    @Test fun likeShowsOnlyOnItsOwnTrack() {
        playback.onRemote(remote(track = track(1)))
        playback.setLiked("spotify:track:1", true)
        assertTrue(shown.track!!.liked)
        now += 100
        playback.onRemote(remote(track = track(2)))
        assertFalse(shown.track!!.liked)
    }

    @Test fun likingTheNextTrackCarriesOverWhenItStarts() {
        playback.onRemote(remote(track = track(1)))
        playback.setLiked("spotify:track:2", true)
        assertFalse(shown.track!!.liked)
        now += 100
        // Spotify's first report for the new track doesn't know about the like yet.
        playback.onRemote(remote(track = track(2, liked = false)))
        assertTrue(shown.track!!.liked)
    }

    @Test fun spotifyGoingAwayShowsNothing() {
        playback.onRemote(remote())
        playback.setPaused(true)
        playback.onRemote(null)
        assertNull(playback.state.value)
    }

    /**
     * Pausing anchors the shown position with a seek override. If the user resumes before Spotify
     * has confirmed the pause, that anchor (timed at the pause) is reused, so the bar extrapolates
     * across the time spent paused and jumps ahead by it, until Spotify's report or the hold
     * corrects it.
     */
    @Ignore("Bug: quick pause then resume jumps the position ahead by the time spent paused")
    @Test fun pauseThenResumeBeforeSpotifyConfirmsKeepsThePosition() {
        playback.onRemote(remote(position = 30_000))
        now += 1_000
        playback.setPaused(true)
        now += 1_000
        playback.setPaused(false)
        assertEquals(31_000, shown.positionAt(now))
    }
}

class PlaybackStateTest {
    private val track = NowPlaying("spotify:track:1", "Song", emptyList(), "Album", null, null, durationMs = 200_000)

    @Test fun positionRunsOnWhilePlaying() {
        val s = PlaybackState(track, isPaused = false, positionMs = 30_000, positionAtMs = 1_000)
        assertEquals(30_000, s.positionAt(1_000))
        assertEquals(35_000, s.positionAt(6_000))
    }

    @Test fun positionFollowsPlaybackSpeed() {
        val s = PlaybackState(track, isPaused = false, positionMs = 30_000, positionAtMs = 0, speed = 1.5f)
        assertEquals(45_000, s.positionAt(10_000))
    }

    @Test fun positionStandsStillWhilePaused() {
        val s = PlaybackState(track, isPaused = true, positionMs = 30_000, positionAtMs = 0)
        assertEquals(30_000, s.positionAt(60_000))
    }

    @Test fun positionStaysWithinTheTrack() {
        val s = PlaybackState(track, isPaused = false, positionMs = 190_000, positionAtMs = 0)
        assertEquals(200_000, s.positionAt(60_000))
        assertEquals(0, s.copy(positionMs = -500, isPaused = true).positionAt(0))
    }

    @Test fun noTrackHasNoPosition() {
        assertEquals(0, PlaybackState(null, isPaused = false, positionMs = 30_000, positionAtMs = 0).positionAt(5_000))
    }

    @Test fun repeatCyclesOffAllOne() {
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertEquals(RepeatMode.One, RepeatMode.All.next())
        assertEquals(RepeatMode.Off, RepeatMode.One.next())
    }
}
