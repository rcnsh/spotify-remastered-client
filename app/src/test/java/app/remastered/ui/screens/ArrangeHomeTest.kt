package app.remastered.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangeHomeTest {
    private fun arrange(shortcuts: List<String>, jump: List<String>, around: List<String> = emptyList(), rediscover: List<String> = emptyList()) =
        arrangeHome(HomeRows(shortcuts, jump, around, rediscover), { it }, shortcuts = 2, cards = 3)

    @Test fun eachItemOnceInRowOrder() {
        val r = arrange(listOf("a", "b", "c"), listOf("a", "c", "d"), listOf("b", "d", "e", "f", "g", "h"), listOf("a", "x"))
        assertEquals(listOf("a", "b"), r.shortcuts)
        assertEquals(listOf("c", "d"), r.jumpBackIn)
        assertEquals(listOf("e", "f", "g"), r.aroundNow)
        assertEquals(listOf("x"), r.rediscover)
    }

    @Test fun anItemPastARowsLimitCanShowLater() {
        // "c" doesn't fit in the shortcuts, so it isn't taken there.
        val r = arrange(listOf("a", "b", "c"), listOf("c"))
        assertEquals(listOf("c"), r.jumpBackIn)
    }

    @Test fun sameListForBothRowsSplitsIt() {
        val all = listOf("a", "b", "c", "d")
        val r = arrange(all, all)
        assertEquals(listOf("a", "b"), r.shortcuts)
        assertEquals(listOf("c", "d"), r.jumpBackIn)
    }
}
