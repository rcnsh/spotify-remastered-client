package app.remastered.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spicy Lyrics' attribution rules: always the provider; uploader and maker only for community syncs. */
class CreditTest {
    private val uploader = Contributor("uploader1", "https://spicylyrics.org/uid/1")
    private val maker = Contributor("maker2", "https://spicylyrics.org/uid/2")

    @Test fun `community sync with a maker credits maker and uploader, linked`() {
        val parts = Attribution(Provider.SpicyLyrics, uploader, maker).credit()
        assertEquals("Synced by maker2, uploaded by uploader1 · Spicy Lyrics", parts.joinToString("") { it.text })
        assertEquals(
            listOf(CreditPart.Link("maker2", "https://spicylyrics.org/uid/2"), CreditPart.Link("uploader1", "https://spicylyrics.org/uid/1")),
            parts.filterIsInstance<CreditPart.Link>(),
        )
    }

    @Test fun `community sync without a maker credits the uploader once`() {
        val parts = Attribution(Provider.SpicyLyrics, uploader).credit()
        assertEquals("Synced by uploader1 · Spicy Lyrics", parts.joinToString("") { it.text })
        assertEquals(1, parts.filterIsInstance<CreditPart.Link>().size)
    }

    @Test fun `contributor without a url is named but not linked`() {
        val parts = Attribution(Provider.SpicyLyrics, Contributor("someone", null)).credit()
        assertEquals("Synced by someone · Spicy Lyrics", parts.joinToString("") { it.text })
        assertEquals(emptyList<CreditPart>(), parts.filterIsInstance<CreditPart.Link>())
    }

    @Test fun `community sync missing its uploader still names the provider`() =
        assertEquals("Lyrics from Spicy Lyrics", Attribution(Provider.SpicyLyrics).creditText())

    @Test fun `commercial sources name only the provider`() {
        assertEquals(listOf(CreditPart.Plain("Lyrics from Apple Music")), Attribution(Provider.AppleMusic).credit())
        assertEquals(listOf(CreditPart.Plain("Lyrics from Spotify")), Attribution(Provider.Spotify).credit())
        // Contributors on a commercial response are never shown.
        assertEquals("Lyrics from Spotify", Attribution(Provider.Spotify, uploader, maker).creditText())
    }

    @Test fun `unknown source says so`() = assertEquals("Lyrics source unknown", Attribution(Provider.Unknown).creditText())
}
