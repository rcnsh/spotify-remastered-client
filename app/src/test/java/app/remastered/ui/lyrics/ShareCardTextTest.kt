package app.remastered.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sizing the share card's lyrics: the biggest text that fits, with a floor. */
class ShareCardTextTest {
    // A fake layout: four rows of text, each 1.2 × the text size tall.
    private val fourRows = { size: Float -> 4 * size * 1.2f }

    @Test fun `short lines get the biggest size`() = assertEquals(96f, ShareCardText.fit(1000f, heightAt = fourRows))

    @Test fun `longer lines step down until they fit`() {
        // 4 × 1.2 × 64 = 307.2, 4 × 1.2 × 72 = 345.6.
        assertEquals(64f, ShareCardText.fit(320f, heightAt = fourRows))
    }

    @Test fun `text that never fits gets the smallest size`() = assertEquals(44f, ShareCardText.fit(10f, heightAt = fourRows))

    @Test fun `sizes are tried largest first`() {
        val tried = mutableListOf<Float>()
        ShareCardText.fit(100f) { tried += it; Float.MAX_VALUE }
        assertEquals(ShareCardText.Sizes, tried)
        assertEquals(ShareCardText.Sizes.sortedDescending(), ShareCardText.Sizes)
    }

    @Test fun `max lines is the rows that fit, at least one`() {
        assertEquals(5, ShareCardText.maxLines(560f, 100f))
        assertEquals(1, ShareCardText.maxLines(40f, 100f))
    }
}
