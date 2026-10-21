package app.remastered.ui.screens

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.Page
import app.remastered.core.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonTest {
    @Test fun songCounts() {
        assertEquals("0 songs", songs(0))
        assertEquals("1 song", songs(1))
        assertEquals("2 songs", songs(2))
    }

    @Test fun totalDurations() {
        assertEquals("0 min", durationText(59_999))
        assertEquals("45 min", durationText(45 * 60_000L + 30_000))
        assertEquals("59 min", durationText(59 * 60_000L))
        assertEquals("2 hr 5 min", durationText(125 * 60_000L))
    }

    @Test fun allPagesReadsUntilTheLastPage() = runTest {
        val items = (1..120).toList()
        val asked = mutableListOf<Int>()
        val all = allPages { offset ->
            asked += offset
            val page = items.drop(offset).take(50)
            Page(page, offset, items.size, offset + page.size < items.size)
        }
        assertEquals(items, all)
        assertEquals(listOf(0, 50, 100), asked)
    }

    @Test fun allPagesStopsAtAnEmptyPage() = runTest {
        var calls = 0
        // A server that says there's more but sends nothing would otherwise loop forever.
        val all = allPages<Int> { calls++; Page(emptyList(), it, 999, hasMore = true) }
        assertEquals(emptyList<Int>(), all)
        assertEquals(1, calls)
    }

    @Test fun allPagesStopsAtTheCap() = runTest {
        val asked = mutableListOf<Int>()
        val all = allPages(max = 100) { offset -> asked += offset; Page(List(50) { offset + it }, offset, null, hasMore = true) }
        assertEquals(100, all.size)
        assertEquals(listOf(0, 50), asked)
    }

    @Test fun filterMatchesNameArtistOrAlbum() {
        val t = Track("t1", "Digital Love", listOf(ArtistRef("a1", "Daft Punk")), AlbumRef("al1", "Discovery"), 0)
        assertTrue(t.matches(""))
        assertTrue(t.matches("   "))
        assertTrue(t.matches("digital"))
        assertTrue(t.matches("  PUNK "))
        assertTrue(t.matches("disco"))
        assertFalse(t.matches("homework"))
        assertTrue(t.copy(album = null).matches("love"))
    }
}
