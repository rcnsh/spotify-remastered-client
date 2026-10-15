package app.remastered.core.warehouse

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.ContextRef
import app.remastered.core.model.Image
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryFeedTest {
    private fun play(ms: Long, track: String = "t$ms", source: String? = "api") =
        Play(ms, track, "Song $track", "Artist", "Album", null, 200_000, null, null, source, null)

    // --- merge with recently-played ---

    @Test fun `recent plays the warehouse already has are dropped, within a few seconds`() {
        val stored = listOf(play(100_000, "a"), play(50_000, "b"))
        val recent = listOf(play(103_000, "a", RECENT_SOURCE), play(50_000, "b", RECENT_SOURCE))
        assertEquals(stored, mergeRecent(stored, recent, complete = false))
    }

    @Test fun `plays newer than the last poll are added on top`() {
        val stored = listOf(play(100_000, "a"))
        val recent = listOf(play(300_000, "c", RECENT_SOURCE), play(200_000, "b", RECENT_SOURCE), play(100_000, "a", RECENT_SOURCE))
        val merged = mergeRecent(stored, recent, complete = false)
        assertEquals(listOf(300_000L, 200_000L, 100_000L), merged.map { it.playedAtMs })
        assertEquals(listOf(RECENT_SOURCE, RECENT_SOURCE, "api"), merged.map { it.source })
    }

    @Test fun `same track outside the tolerance is a second play`() {
        val stored = listOf(play(100_000, "a"))
        val merged = mergeRecent(stored, listOf(play(100_000 + SAME_PLAY_TOLERANCE_MS + 1, "a", RECENT_SOURCE)), complete = false)
        assertEquals(2, merged.size)
    }

    @Test fun `a different track at the same time is kept`() {
        val merged = mergeRecent(listOf(play(100_000, "a")), listOf(play(100_000, "b", RECENT_SOURCE)), complete = false)
        assertEquals(setOf("a", "b"), merged.map { it.trackId }.toSet())
    }

    @Test fun `recent plays older than the paged range wait for paging`() {
        val stored = listOf(play(100_000, "a"))
        val older = play(10_000, "z", RECENT_SOURCE)
        assertEquals(stored, mergeRecent(stored, listOf(older), complete = false))
        assertEquals(listOf(stored[0], older), mergeRecent(stored, listOf(older), complete = true))
    }

    @Test fun `with nothing stored, recent plays are the history`() {
        val recent = listOf(play(2_000, "b", RECENT_SOURCE), play(1_000, "a", RECENT_SOURCE))
        assertEquals(recent, mergeRecent(emptyList(), recent, complete = false))
    }

    @Test fun `a recently-played entry maps onto a play`() {
        val t = Track("id22charactersxxxxxxxx", "Song", listOf(ArtistRef("x", "One"), ArtistRef("y", "Two")),
            AlbumRef("al", "Album", listOf(Image("small", 64), Image("big", 640))), 180_000)
        val p = PlayHistory(t, 1_000, ContextRef("spotify:playlist:p", "playlist")).toPlay()
        assertEquals("One, Two", p.artists)
        assertEquals("Album", p.album)
        assertEquals("big", p.imageUrl)
        assertEquals("spotify:playlist:p", p.contextUri)
        assertEquals(RECENT_SOURCE, p.source)
        assertEquals("spotify:track:id22charactersxxxxxxxx", p.trackUri)
    }

    // --- paging ---

    /** A fake `/api/plays` over [all] (any order): newest first, `before` exclusive. */
    private class FakePlays(all: List<Play>) {
        val sorted = all.sortedByDescending { it.playedAtMs }
        val calls = mutableListOf<Long?>()
        suspend fun fetch(before: Long?, limit: Int): List<Play> {
            calls += before
            return sorted.filter { before == null || it.playedAtMs < before }.take(limit)
        }
    }

    @Test fun `pages back by before until a short page`() = runTest {
        val api = FakePlays((1..7).map { play(it * 1_000L) })
        val pager = PlayPager(pageSize = 3, fetch = api::fetch)
        // Each page asks from one past the oldest so same-millisecond plays aren't skipped, so it
        // gets the oldest again and adds one fewer.
        assertEquals(listOf(3, 2, 2), List(3) { pager.loadMore().size })
        assertFalse(pager.done)
        assertEquals(0, pager.loadMore().size)
        assertTrue(pager.done)
        assertEquals(emptyList<Play>(), pager.loadMore())
        assertEquals((7 downTo 1).map { it * 1_000L }, pager.plays.map { it.playedAtMs })
        assertEquals(listOf(null, 5_001L, 3_001L, 1_001L), api.calls)
    }

    @Test fun `plays sharing a millisecond across a page boundary all arrive once`() = runTest {
        val api = FakePlays(listOf(play(5_000, "a"), play(4_000, "b"), play(4_000, "c"), play(3_000, "d")))
        val pager = PlayPager(pageSize = 2, fetch = api::fetch)
        while (!pager.done) pager.loadMore()
        assertEquals(listOf("a", "b", "c", "d").sorted(), pager.plays.map { it.trackId }.sorted())
        assertEquals(4, pager.plays.size)
    }

    @Test fun `a full page of ties steps past that millisecond instead of looping`() = runTest {
        val api = FakePlays(listOf(play(5_000, "a"), play(5_000, "b"), play(1_000, "z")))
        val pager = PlayPager(pageSize = 2, fetch = api::fetch)
        pager.loadMore()
        assertEquals(emptyList<Play>(), pager.loadMore())
        assertEquals(5_000L, pager.nextBeforeMs)
        assertEquals(listOf("z"), pager.loadMore().map { it.trackId })
        assertTrue(pager.done)
    }

    @Test fun `starts before an anchor`() = runTest {
        val api = FakePlays((1..5).map { play(it * 1_000L) })
        val pager = PlayPager(startBeforeMs = 3_000, pageSize = 10, fetch = api::fetch)
        pager.loadMore()
        assertEquals(listOf(2_000L, 1_000L), pager.plays.map { it.playedAtMs })
        assertEquals(3_000L, api.calls.single())
    }

    @Test fun `an empty warehouse is done at once`() = runTest {
        val pager = PlayPager(fetch = FakePlays(emptyList())::fetch)
        pager.loadMore()
        assertTrue(pager.done)
        assertNull(pager.nextBeforeMs)
    }

    // --- days ---

    @Test fun `groups by local day, not UTC`() {
        val zone = ZoneId.of("Asia/Singapore") // UTC+8
        // 2026-10-12 17:00 UTC is 13 Oct 01:00 in Singapore; 15:00 UTC is 12 Oct 23:00.
        val late = play(LocalDate.of(2026, 10, 12).atStartOfDay(ZoneId.of("UTC")).plusHours(17).toInstant().toEpochMilli())
        val early = play(late.playedAtMs - 2 * 3_600_000)
        val days = groupByDay(listOf(late, early), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 12)), days.map { it.first })
    }

    @Test fun `consecutive plays on a day stay together`() {
        val zone = ZoneId.of("UTC")
        val base = LocalDate.of(2026, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = groupByDay(listOf(play(base + 3), play(base + 2), play(base - 1)), zone)
        assertEquals(listOf(2, 1), days.map { it.second.size })
    }

    @Test fun `end of day is the next local midnight`() {
        val zone = ZoneId.of("Europe/London")
        // The clocks go back on 25 Oct 2026: that day is 25 hours long.
        val end = endOfDayMs(LocalDate.of(2026, 10, 25), zone)
        val start = LocalDate.of(2026, 10, 25).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(25 * 3_600_000L, end - start)
    }

    // --- Home's contexts ---

    private fun from(ms: Long, context: String?) = play(ms).copy(contextUri = context)

    @Test fun `contexts are distinct, newest first, and stop at want`() = runTest {
        val api = FakePlays(listOf(
            from(9_000, "spotify:album:a"), from(8_000, "spotify:album:a"), from(7_000, null),
            from(6_000, "spotify:playlist:p"), from(5_000, "spotify:artist:r"), from(4_000, "spotify:album:b"),
        ))
        val got = recentContexts(want = 3, pageSize = 2, fetch = api::fetch)
        assertEquals(listOf(ContextRef("spotify:album:a", "album"), ContextRef("spotify:playlist:p", "playlist"),
            ContextRef("spotify:artist:r", "artist")), got)
    }

    @Test fun `contexts skip what Home can't open and stop at maxPages`() = runTest {
        val api = FakePlays(listOf(
            from(9_000, "spotify:album:a"), from(8_000, "spotify:user:u:collection"), from(7_000, "spotify:show:s"),
            from(6_000, "spotify:album:a"), from(5_000, "spotify:album:b"), from(1_000, "spotify:album:c"),
        ))
        assertEquals(listOf("spotify:album:a"), recentContexts(want = 5, maxPages = 2, pageSize = 2, fetch = api::fetch).map { it.uri })
        assertEquals(2, api.calls.size)
    }

    @Test fun `contexts end with the history`() = runTest {
        val api = FakePlays(listOf(from(2_000, "spotify:album:a")))
        assertEquals(1, recentContexts(want = 5, pageSize = 2, fetch = api::fetch).size)
        assertEquals(1, api.calls.size)
    }
}
