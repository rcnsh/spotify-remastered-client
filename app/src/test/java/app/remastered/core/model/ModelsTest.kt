package app.remastered.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun bestImageIsTheSmallestBigEnough() {
        val images = listOf(Image("l", 640, 640), Image("s", 64, 64), Image("m", 300, 300))
        assertEquals("m", images.best()!!.url)
        assertEquals("s", images.best(minPx = 50)!!.url)
    }

    @Test fun bestImageFallsBackToTheLargest() {
        assertEquals("m", listOf(Image("s", 64, 64), Image("m", 160, 160)).best()!!.url)
        assertNull(emptyList<Image>().best())
    }

    @Test fun anImageOfUnknownSizeCountsAsBig() {
        // App Remote images have no size; a sized one that's big enough is still preferred.
        assertEquals("l", listOf(Image("appremote:x"), Image("l", 640, 640)).best()!!.url)
        assertEquals("appremote:x", listOf(Image("appremote:x"), Image("s", 64, 64)).best()!!.url)
    }

    @Test fun onlyOwnOrCollaborativePlaylistsCanBeReadAndEdited() {
        val me = UserRef("me", "Me")
        fun playlist(owner: String, collaborative: Boolean = false) =
            Playlist("p", "P", "", emptyList(), UserRef(owner, owner), 0, collaborative, "s")
        assertTrue(playlist("me").canReadItems(me))
        assertTrue(playlist("me").canEdit(me))
        assertFalse(playlist("them").canReadItems(me))
        assertTrue(playlist("them", collaborative = true).canEdit(me))
        // Before the profile has loaded, only collaborative playlists count.
        assertFalse(playlist("me").canReadItems(null))
    }

    @Test fun urisByKind() {
        val track = Track("t1", "Song", listOf(ArtistRef("a1", "One"), ArtistRef("a2", "Two")), null, 0)
        assertEquals("spotify:track:t1", track.uri)
        assertEquals("spotify:episode:t1", track.copy(isEpisode = true).uri)
        assertEquals("One, Two", track.artistNames)
        assertEquals("spotify:album:al", AlbumRef("al", "A").uri)
        assertEquals("spotify:artist:a1", ArtistRef("a1", "One").uri)
    }

    @Test fun albumYearFromAnyReleaseDatePrecision() {
        fun album(date: String) = Album("a", "A", emptyList(), emptyList(), AlbumType.Album, date, 1)
        assertEquals("2013", album("2013").year)
        assertEquals("2013", album("2013-10").year)
        assertEquals("2013", album("2013-10-02").year)
        assertEquals("", album("").year)
    }
}
