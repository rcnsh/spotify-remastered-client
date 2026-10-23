package app.remastered.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking lines for a share card: consecutive, one to four, grown and shrunk by taps. */
class LineSelectionTest {
    private fun sel(first: Int, last: Int) = LineSelection(first, last)

    @Test fun `a long press selects that one line`() = assertEquals(sel(5, 5), LineSelection.start(5))

    @Test fun `tapping a neighbour adds it on either side`() {
        assertEquals(sel(5, 6), sel(5, 5).tap(6))
        assertEquals(sel(4, 6), sel(5, 6).tap(4))
    }

    @Test fun `tapping an end line removes it`() {
        assertEquals(sel(5, 6), sel(4, 6).tap(4))
        assertEquals(sel(4, 5), sel(4, 6).tap(6))
    }

    @Test fun `tapping the only selected line ends selection`() = assertNull(sel(5, 5).tap(5))

    @Test fun `tapping a line inside cuts the selection off after it`() = assertEquals(sel(2, 3), sel(2, 5).tap(3))

    @Test fun `tapping a line further away starts over from it`() {
        assertEquals(sel(9, 9), sel(2, 3).tap(9))
        assertEquals(sel(0, 0), sel(2, 3).tap(0))
    }

    @Test fun `a full selection keeps its lines when a neighbour is tapped`() {
        val full = sel(2, 5)
        assertEquals(LineSelection.MAX, full.size)
        assertFalse(full.canAdd(6))
        assertEquals(full, full.tap(6))
        assertEquals(full, full.tap(1))
    }

    @Test fun `only neighbours can be added`() {
        assertTrue(sel(2, 3).canAdd(1))
        assertTrue(sel(2, 3).canAdd(4))
        assertFalse(sel(2, 3).canAdd(5))
        assertFalse(sel(2, 3).canAdd(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `more than four lines is refused`() { sel(0, LineSelection.MAX) }

    @Test fun `texts are the selected lines, trimmed, without blanks`() {
        val lines = listOf("one", " two ", "", "four", "five")
        assertEquals(listOf("two", "four"), sel(1, 3).texts(lines))
        // A selection reaching past the end (the lyrics changed under it) is cut to what's there.
        assertEquals(listOf("five"), sel(4, 6).texts(lines))
    }
}
