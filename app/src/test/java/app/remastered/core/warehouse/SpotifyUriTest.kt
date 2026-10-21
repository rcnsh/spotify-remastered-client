package app.remastered.core.warehouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyUriTest {
    @Test fun realIdsBecomeUris() {
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", spotifyUri("track", "4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("spotify:artist:0TnOYISbd1XYRBk9myaseg", spotifyUri("artist", "0TnOYISbd1XYRBk9myaseg"))
    }

    @Test fun placeholdersAndBlanksDont() {
        // Plays imported from the streaming history export can carry name: placeholders.
        assertNull(spotifyUri("track", "name:Digital Love|Daft Punk"))
        assertNull(spotifyUri("track", ""))
        assertNull(spotifyUri("track", null))
        assertNull(spotifyUri("track", "4uLU6hMCjMI75M1A2tKUQ")) // 21 characters
        assertNull(spotifyUri("track", "4uLU6hMCjMI75M1A2tKUQC1"))
        assertNull(spotifyUri("track", "4uLU6hMCjMI75M1A2tKU-C"))
    }
}
