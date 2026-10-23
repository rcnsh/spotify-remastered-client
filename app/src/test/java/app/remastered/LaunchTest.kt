package app.remastered

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchTest {
    @Test fun actionsMapToWhereTheyGo() {
        assertEquals(Launch.NowPlaying, Launch.of(MainActivity.ACTION_OPEN_NOW_PLAYING))
        Launch.entries.forEach { assertEquals(it, Launch.of(it.action)) }
        assertNull(Launch.of(null))
        assertNull(Launch.of("android.intent.action.MAIN"))
    }

    /** The static shortcuts' actions are spelled out in XML: they have to match [Launch]. */
    @Test fun staticShortcutsUseKnownActions() {
        val xml = File("src/main/res/xml/shortcuts.xml").readText()
        val actions = Regex("android:action=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf(Launch.NowPlaying, Launch.LikedSongs, Launch.Search), actions.map(Launch::of))
    }
}
