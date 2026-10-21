package app.remastered.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test fun spotifyUrisOpenTheirScreens() {
        assertEquals(Route.Playlist("p1"), Route.of("spotify:playlist:p1"))
        assertEquals(Route.Album("a1"), Route.of("spotify:album:a1"))
        assertEquals(Route.Artist("r1"), Route.of("spotify:artist:r1"))
        assertEquals(Route.Show("s1"), Route.of("spotify:show:s1"))
    }

    @Test fun likedSongsInEveryForm() {
        assertEquals(Route.LikedSongs, Route.of("spotify:collection:tracks"))
        assertEquals(Route.LikedSongs, Route.of("spotify:user:someone:collection"))
    }

    @Test fun oldUserPlaylistUris() {
        assertEquals(Route.Playlist("p1"), Route.of("spotify:user:someone:playlist:p1"))
        assertNull(Route.of("spotify:user:someone:playlist"))
        assertNull(Route.of("spotify:user:someone"))
    }

    @Test fun nothingForUrisWithoutAScreen() {
        assertNull(Route.of("spotify:track:t1"))
        assertNull(Route.of("spotify:episode:e1"))
        assertNull(Route.of("spotify:album"))
        assertNull(Route.of("https://open.spotify.com/album/a1"))
        assertNull(Route.of(""))
    }
}

class NavigatorTest {
    private val nav = Navigator()

    @Test fun startsAtHome() {
        assertEquals(Tab.Home, nav.tab)
        assertEquals(Route.Home, nav.current)
        assertEquals(1, nav.depth)
    }

    @Test fun pushAndPop() {
        nav.push(Route.Album("a1"))
        nav.push(Route.Artist("r1"))
        assertEquals(3, nav.depth)
        assertTrue(nav.pop())
        assertEquals(Route.Album("a1"), nav.current)
    }

    @Test fun openingTheSameScreenTwiceDoesntStackIt() {
        nav.push(Route.Album("a1"))
        nav.push(Route.Album("a1"))
        assertEquals(2, nav.depth)
        nav.push(Route.Album("a2"))
        assertEquals(3, nav.depth)
    }

    @Test fun eachTabKeepsItsOwnStack() {
        nav.push(Route.Album("a1"))
        nav.select(Tab.Library)
        assertEquals(Route.Library, nav.current)
        nav.push(Route.Playlist("p1"))
        nav.select(Tab.Home)
        assertEquals(Route.Album("a1"), nav.current)
        nav.select(Tab.Library)
        assertEquals(Route.Playlist("p1"), nav.current)
    }

    @Test fun tappingTheCurrentTabGoesBackToItsRoot() {
        nav.push(Route.Album("a1"))
        nav.push(Route.Artist("r1"))
        nav.select(Tab.Home)
        assertEquals(Route.Home, nav.current)
        assertEquals(1, nav.depth)
    }

    @Test fun backFromAnotherTabsRootGoesHomeThenLeaves() {
        nav.push(Route.Album("a1"))
        nav.select(Tab.Search)
        assertTrue(nav.pop())
        assertEquals(Tab.Home, nav.tab)
        // Home keeps where it was.
        assertEquals(Route.Album("a1"), nav.current)
        assertTrue(nav.pop())
        assertFalse(nav.pop())
    }
}
