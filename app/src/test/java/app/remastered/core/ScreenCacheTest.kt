package app.remastered.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L
    private val cache = ScreenCache({ now }, capacity = 2)

    @Test fun freshUntilMaxAge() {
        cache.put("home", "data", emptyList())
        now = 59_999
        assertTrue(cache.isFresh(cache.get("home")!!, emptyList(), 60_000))
        now = 60_000
        assertFalse(cache.isFresh(cache.get("home")!!, emptyList(), 60_000))
    }

    @Test fun changedKeysMakeItStale() {
        cache.put("playlist:1", "data", listOf(0))
        val e = cache.get("playlist:1")!!
        assertFalse(cache.isFresh(e, listOf(1), 60_000))
        assertEquals("data", e.value) // still shown while it reloads
    }

    @Test fun updateKeepsLoadTimeAndKeys() {
        cache.put("playlist:1", "old order", listOf(0))
        now = 30_000
        cache.update("playlist:1", "new order")
        cache.update("playlist:2", "never loaded")
        val e = cache.get("playlist:1")!!
        assertEquals("new order", e.value)
        assertEquals(0L, e.loadedAt)
        assertEquals(listOf(0), e.version)
        assertNull(cache.get("playlist:2"))
    }

    @Test fun dropsLeastRecentlyUsed() {
        cache.put("a", 1, emptyList())
        cache.put("b", 2, emptyList())
        cache.get("a")
        cache.put("c", 3, emptyList())
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a")?.value)
        assertEquals(2, cache.size)
    }

    @Test fun clear() {
        cache.put("a", 1, emptyList())
        cache.clear()
        assertNull(cache.get("a"))
    }

    @Test fun savedEntryIsRestoredInANewRunAndAgesByWallClock() {
        val dir = tmp.newFolder("screens")
        var wall = 1_700_000_000_000L
        ScreenCache({ now }, dir = dir, wallClock = { wall }).save("library", listOf("a", "b"), listOf(0), ListSerializer(String.serializer()))

        // A new process: memory is empty, and time since boot started again.
        now = 5_000
        wall += 10 * 60_000
        val next = ScreenCache({ now }, dir = dir, wallClock = { wall })
        assertNull(next.get("library"))
        val e = next.restore("library", ListSerializer(String.serializer()))!!
        assertEquals(listOf("a", "b"), e.value)
        assertTrue(next.isFresh(e, listOf(0), 30 * 60_000)) // ten minutes old
        assertFalse(next.isFresh(e, listOf(0), 5 * 60_000))
        assertFalse(next.isFresh(e, listOf(1), 30 * 60_000))
        assertEquals(e, next.get("library"))

        next.clearDisk()
        assertNull(ScreenCache({ now }, dir = dir, wallClock = { wall }).restore("library", ListSerializer(String.serializer())))
    }

    @Test fun unreadableSavedEntryIsIgnored() {
        val dir = tmp.newFolder("screens")
        dir.resolve("library.json").writeText("{nope")
        assertNull(ScreenCache({ now }, dir = dir).restore("library", ListSerializer(String.serializer())))
    }
}
