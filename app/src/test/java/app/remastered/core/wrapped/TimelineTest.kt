package app.remastered.core.wrapped

import app.remastered.core.warehouse.DayRun
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val data = WrappedFixture.load()

    /** What the web app's `timeline()` gives for the same fixture (node, music-warehouse-wrapped). */
    @Test fun `places every slide as the web does`() {
        val t = WrappedTimeline.of(data)
        val expected = listOf(
            "intro" to (0 to 150), "minutes" to (134 to 195), "clock" to (313 to 180), "artist-tease" to (477 to 90),
            "artist" to (551 to 195), "artists" to (730 to 195), "song" to (909 to 195), "songs" to (1088 to 240),
            "album" to (1312 to 165), "age" to (1461 to 195), "discoveries" to (1640 to 195), "months" to (1819 to 240),
            "streak" to (2043 to 165), "summary" to (2192 to 270),
        )
        assertEquals(expected, t.slides.map { it.id.key to (it.from to it.durationInFrames) })
        assertEquals(2462, t.durationInFrames)
        assertEquals(2192 + 110, t.summaryCardFrame)
    }

    @Test fun `when rules drop slides the data can't support`() {
        val t = WrappedTimeline.of(
            data.copy(
                topArtists = data.topArtists.take(2), listeningAge = null, months = data.months.take(2),
                streak = DayRun(2, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2)),
                discoveries = data.discoveries.copy(share = 0.95),
            ),
        )
        assertEquals(
            listOf("intro", "minutes", "clock", "artist-tease", "artist", "song", "songs", "album", "summary"),
            t.slides.map { it.id.key },
        )
        // Overlaps shift with what's left: artist (551..746) is followed directly by song.
        assertEquals(551 + 195 - 16, t.find(SlideId.Song)!!.from)
    }

    @Test fun `an empty range is just the intro`() {
        val t = WrappedTimeline.of(
            WrappedFixture.load { o ->
                JsonObject(o + mapOf(
                    "top_artists" to JsonArray(emptyList()), "top_songs" to JsonArray(emptyList()), "top_albums" to JsonArray(emptyList()),
                    "months" to JsonArray(emptyList()), "listening_age" to JsonNull, "streak" to JsonNull,
                    "hours" to JsonArray(List(24) { JsonPrimitive(0) }),
                    "totals" to JsonObject(o.getValue("totals").jsonObject + ("minutes" to JsonPrimitive(0))),
                    "discoveries" to JsonObject(o.getValue("discoveries").jsonObject + ("artists" to JsonPrimitive(0))),
                ))
            },
        )
        assertEquals(listOf(SlideId.Intro), t.slides.map { it.id })
        assertEquals(150, t.durationInFrames)
        assertNull(t.summaryCardFrame)
    }

    @Test fun `the shown slide flips when the next one's transition starts`() {
        val t = WrappedTimeline.of(data)
        assertEquals(0, t.indexAt(0))
        assertEquals(0, t.indexAt(133))
        assertEquals(1, t.indexAt(134))
        assertEquals(13, t.indexAt(99_999))
        // Both slides draw while they overlap, the outgoing one underneath.
        assertEquals(listOf(SlideId.Intro to 140, SlideId.Minutes to 6), t.visibleAt(140).map { it.first.id to it.second })
        assertEquals(listOf(SlideId.Minutes to 16), t.visibleAt(150).map { it.first.id to it.second })
    }

    @Test fun `progress bars fill like the web story's`() {
        val t = WrappedTimeline.of(data)
        assertEquals(1.0, t.barFill(0, 200), 0.0)
        assertEquals(0.0, t.barFill(2, 200), 0.0)
        assertEquals((200 - 134) / (313.0 - 134), t.barFill(1, 200), 1e-9)
        assertEquals(1.0, t.barFill(13, 2461 + 50), 0.0)
    }

    @Test fun `songs fixture keeps its months`() {
        assertTrue(data.months.size == 12)
        assertEquals(5, data.topSongs.size)
        assertEquals(1, WrappedFixture.raw().getValue("top_albums").jsonArray.size)
    }
}
