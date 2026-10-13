package app.remastered.core.wrapped

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {
    /** A monospace stand-in: every character is half the font size wide. */
    private val mono = TextWidth { text, size -> text.length * size * 0.5 }

    @Test fun `fitText fills the width exactly`() {
        // "2025" at 100 px is 200 wide, so 920 wide needs 460 px.
        assertEquals(460.0, fitText("2025", 920.0, mono), 1e-9)
    }

    @Test fun `fitTextOnNLines wraps and takes the largest size that fits`() {
        val fit = fitTextOnNLines("THE AFTERNOON DRIFTER", 900.0, 2, 200.0, mono)
        // Two lines: the longest is "THE AFTERNOON" (13 chars) → 13 * 0.5 * s <= 900 → s <= 138.46.
        assertEquals(listOf("THE AFTERNOON", "DRIFTER"), fit.lines)
        assertEquals(138.46, fit.fontSize, 0.011)
    }

    @Test fun `fitTextOnNLines stops at the cap for short text`() {
        val fit = fitTextOnNLines("ABBA", 920.0, 2, 220.0, mono)
        assertEquals(220.0, fit.fontSize, 0.0)
        assertEquals(listOf("ABBA"), fit.lines)
    }

    @Test fun `a word wider than the box shrinks the type`() {
        val fit = fitTextOnNLines("SUPERCALIFRAGILISTIC X", 480.0, 3, 130.0, mono)
        assertTrue(fit.fontSize * 0.5 * "SUPERCALIFRAGILISTIC".length <= 480.0)
        assertEquals("SUPERCALIFRAGILISTIC", fit.lines.first())
    }
}
