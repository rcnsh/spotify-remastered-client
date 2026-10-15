package app.remastered.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextTilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_700_000_000_000L
    private val tile = ContextTiles.Tile("Mix", "Playlist  ·  Ada", "https://i/1")

    private fun TestScope.tiles() = ContextTiles(tmp.root.resolve("tiles.json"), { now }, io = StandardTestDispatcher(testScheduler))

    @Test fun savedTilesOutliveTheProcess() = runTest {
        val first = tiles()
        assertNull(first.get("spotify:playlist:p1"))
        first.put("spotify:playlist:p1", tile)
        first.save()
        assertEquals(tile, tiles().get("spotify:playlist:p1")?.tile)
    }

    @Test fun tilesExpire() = runTest {
        val tiles = tiles()
        tiles.put("spotify:playlist:p1", tile)
        now += 6 * DAY
        assertNotNull(tiles.get("spotify:playlist:p1"))
        now += 2 * DAY
        assertNull(tiles.get("spotify:playlist:p1"))
    }

    @Test fun missesAreRememberedForLessLong() = runTest {
        val tiles = tiles()
        tiles.put("spotify:playlist:editorial", null)
        val hit = tiles.get("spotify:playlist:editorial")
        assertNotNull(hit)
        assertNull(hit?.tile)
        now += DAY + 1
        assertNull(tiles.get("spotify:playlist:editorial"))
    }

    @Test fun clearForgetsEverything() = runTest {
        val tiles = tiles()
        tiles.put("spotify:artist:a1", tile)
        tiles.save()
        tiles.clear()
        assertNull(tiles.get("spotify:artist:a1"))
        assertNull(tiles().get("spotify:artist:a1"))
    }

    @Test fun unreadableFileCountsAsEmpty() = runTest {
        tmp.root.resolve("tiles.json").writeText("{nope")
        assertNull(tiles().get("spotify:artist:a1"))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
