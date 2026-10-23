package app.remastered.media

import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import app.remastered.core.model.ArtistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val track = NowPlaying("spotify:track:1", "Song", listOf(ArtistRef("a", "One"), ArtistRef("b", "Two")), "Album", null,
        "https://i.scdn.co/image/x", durationMs = 200_000, liked = true)
    private fun state(paused: Boolean = false, track: NowPlaying? = this.track) =
        PlaybackState(track, isPaused = paused, positionMs = 0, positionAtMs = 0)

    @Test fun idleWithoutSpotifyOrATrack() {
        assertEquals(WidgetModel.Idle, WidgetModel.of(null))
        assertEquals(WidgetModel.Idle, WidgetModel.of(state(track = null)))
    }

    @Test fun showsTheTrack() {
        val m = WidgetModel.of(state()) as WidgetModel.Track
        assertEquals("Song", m.title)
        assertEquals("One, Two", m.artist)
        assertEquals("https://i.scdn.co/image/x", m.imageUrl)
        assertTrue(m.playing)
        assertTrue(m.canLike)
        assertTrue(m.liked)
    }

    @Test fun pausedAndRestricted() {
        val m = WidgetModel.of(state(paused = true).copy(canSkipNext = false, canSkipPrevious = false)) as WidgetModel.Track
        assertFalse(m.playing)
        assertFalse(m.canSkipNext)
        assertFalse(m.canSkipPrevious)
    }

    @Test fun episodesCantBeLiked() {
        val m = WidgetModel.of(state(track = track.copy(isEpisode = true, liked = false))) as WidgetModel.Track
        assertFalse(m.canLike)
    }

    @Test fun positionChangesDontRedraw() {
        assertEquals(WidgetModel.of(state()), WidgetModel.of(state().copy(positionMs = 90_000, positionAtMs = 5_000)))
    }
}
