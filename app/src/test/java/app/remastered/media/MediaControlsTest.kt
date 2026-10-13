package app.remastered.media

import android.media.session.PlaybackState as SessionState
import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlsTest {
    private val track = NowPlaying("spotify:track:1", "Song", emptyList(), "Album", null, null, durationMs = 200_000)
    private fun state(paused: Boolean = false, track: NowPlaying? = this.track) =
        PlaybackState(track, isPaused = paused, positionMs = 30_000, positionAtMs = 5_000, speed = 1f)

    private fun has(c: MediaControls, action: Long) = c.actions and action != 0L

    @Test fun nothingToShowWithoutATrack() {
        assertNull(MediaControls.of(null))
        assertNull(MediaControls.of(state(track = null)))
    }

    @Test fun playingOffersPauseSkipAndSeek() {
        val c = MediaControls.of(state())!!
        assertTrue(c.playing)
        assertTrue(has(c, SessionState.ACTION_PAUSE))
        assertFalse(has(c, SessionState.ACTION_PLAY))
        assertTrue(has(c, SessionState.ACTION_SKIP_TO_NEXT))
        assertTrue(has(c, SessionState.ACTION_SKIP_TO_PREVIOUS))
        assertTrue(has(c, SessionState.ACTION_SEEK_TO))
        assertEquals(30_000, c.positionMs)
        assertEquals(5_000, c.positionAtMs)
        assertEquals(1f, c.speed)
    }

    @Test fun pausedOffersPlayAndStandsStill() {
        val c = MediaControls.of(state(paused = true))!!
        assertFalse(c.playing)
        assertTrue(has(c, SessionState.ACTION_PLAY))
        assertFalse(has(c, SessionState.ACTION_PAUSE))
        assertEquals(0f, c.speed)
    }

    @Test fun restrictionsDropTheirActions() {
        val c = MediaControls.of(state().copy(canSeek = false, canSkipNext = false, canSkipPrevious = false))!!
        assertFalse(has(c, SessionState.ACTION_SEEK_TO))
        assertFalse(has(c, SessionState.ACTION_SKIP_TO_NEXT))
        assertFalse(has(c, SessionState.ACTION_SKIP_TO_PREVIOUS))
        assertTrue(has(c, SessionState.ACTION_PLAY_PAUSE))
    }

    @Test fun episodesCantBeLiked() {
        assertTrue(MediaControls.of(state(track = track.copy(liked = true)))!!.let { it.canLike && it.liked })
        assertFalse(MediaControls.of(state(track = track.copy(isEpisode = true)))!!.canLike)
    }
}
