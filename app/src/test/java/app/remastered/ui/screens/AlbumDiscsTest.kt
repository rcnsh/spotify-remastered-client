package app.remastered.ui.screens

import app.remastered.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumDiscsTest {
    private fun track(n: Int?, disc: Int?) = Track("t$n-$disc", "Song $n", emptyList(), null, 1000, trackNumber = n, discNumber = disc)

    @Test fun singleDiscIsOneGroup() {
        val d = discs(listOf(track(1, 1), track(2, 1), track(3, 1)))
        assertEquals(1, d.size)
        assertEquals(3, d[0].tracks.size)
    }

    @Test fun splitsOnDiscNumberAndKeepsAlbumIndex() {
        val d = discs(listOf(track(1, 1), track(2, 1), track(1, 2), track(2, 2), track(3, 2)))
        assertEquals(listOf(1, 2), d.map { it.number })
        assertEquals(listOf(2, 3, 4), d[1].tracks.map { it.index })
    }

    @Test fun fallsBackToTrackNumberReset() {
        val d = discs(listOf(track(1, null), track(2, null), track(1, null), track(2, null)))
        assertEquals(listOf(1, 2), d.map { it.number })
    }

    @Test fun contextPositionsSkipUnplayableTracks() {
        val removed = track(3, 1).copy(isPlayable = false)
        val tracks = listOf(track(1, 1), track(2, 1), removed, track(4, 1), track(5, 1))
        assertEquals(listOf(0, 1, null, 2, 3), contextPositions(tracks))
    }

    @Test fun noNumbersStaysOneDisc() {
        assertEquals(1, discs(listOf(track(null, null), track(null, null))).size)
    }

    @Test fun emptyAlbum() {
        assertEquals(0, discs(emptyList()).size)
    }
}
