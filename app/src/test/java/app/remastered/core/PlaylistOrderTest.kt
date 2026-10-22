package app.remastered.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistOrderTest {
    /** Applies a move to a list the way Spotify does, for checking against [PlaylistOrder.shift]. */
    private fun <T> List<T>.move(from: Int, before: Int): List<T> {
        val out = toMutableList()
        val item = out.removeAt(from)
        out.add(if (before > from) before - 1 else before, item)
        return out
    }

    @Test fun upDropsBeforeTheItemBelow() {
        assertEquals(0, PlaylistOrder.insertBefore(3, above = null, below = 0))
    }

    @Test fun toTheEndGoesAfterTheLastItem() {
        assertEquals(6, PlaylistOrder.insertBefore(2, above = 5, below = null))
    }

    @Test fun droppedWhereItWasIsNoMove() {
        assertNull(PlaylistOrder.insertBefore(2, above = 1, below = 3))
        assertNull(PlaylistOrder.insertBefore(0, above = null, below = 1))
        assertNull(PlaylistOrder.insertBefore(0, above = null, below = null))
    }

    @Test fun hiddenItemsKeepTheirPlace() {
        // Position 2 is a local file the app hides; dragging 1 below 3 skips over it.
        assertEquals(4, PlaylistOrder.insertBefore(1, above = 3, below = 4))
        // Dragging 3 up to just under 1 lands before the hidden 2.
        assertEquals(2, PlaylistOrder.insertBefore(3, above = 1, below = 2))
    }

    @Test fun shiftMatchesSpotifysMove() {
        val list = (0 until 6).toList()
        for (from in list) for (before in 0..list.size) {
            if (before == from || before == from + 1) continue
            val moved = list.move(from, before)
            for (p in list) assertEquals("from $from before $before item $p", moved.indexOf(p), PlaylistOrder.shift(p, from, before))
        }
    }
}
