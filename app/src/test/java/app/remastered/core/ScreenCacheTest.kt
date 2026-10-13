package app.remastered.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCacheTest {
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
}
