package app.remastered.core.warehouse

import app.remastered.core.model.ContextRef
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.best
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The History list: the warehouse's plays paged back by `before`, with the Web API's
 * recently-played merged on top for what the warehouse hasn't polled yet (it polls every 30 min).
 */

/** Plays this close together of the same track are one play seen twice (export vs API rounding). */
const val SAME_PLAY_TOLERANCE_MS = 5_000L

/** Marks a [Play] that came from the Web API's recently-played rather than the warehouse. */
const val RECENT_SOURCE = "recent"

/** A recently-played entry as a [Play], so it can sit in the same list. */
fun PlayHistory.toPlay(): Play = Play(
    playedAtMs = playedAtEpochMs,
    trackId = track.id,
    name = track.name,
    artists = track.artistNames,
    album = track.album?.name,
    imageUrl = track.album?.images?.best(120)?.url,
    durationMs = track.durationMs,
    contextUri = context?.uri,
    contextType = context?.type,
    source = RECENT_SOURCE,
    isrc = null,
)

fun Play.isSamePlay(other: Play, toleranceMs: Long = SAME_PLAY_TOLERANCE_MS) =
    trackId == other.trackId && abs(playedAtMs - other.playedAtMs) <= toleranceMs

/**
 * [stored] (newest first, as paged so far) with the [recent] plays it doesn't have yet, newest
 * first. A recent play counts as stored when the same track was stored within [toleranceMs].
 *
 * Recent plays older than the oldest stored one are left out until paging reaches them (unless
 * [complete]: the warehouse has nothing older), so they can't appear out of order and then be
 * duplicated by the next page.
 */
fun mergeRecent(stored: List<Play>, recent: List<Play>, complete: Boolean, toleranceMs: Long = SAME_PLAY_TOLERANCE_MS): List<Play> {
    if (recent.isEmpty()) return stored
    val oldest = stored.minOfOrNull { it.playedAtMs }
    val byTrack = stored.groupBy { it.trackId }
    val extra = recent.asSequence()
        .filter { complete || oldest == null || it.playedAtMs >= oldest }
        .filter { r -> byTrack[r.trackId].orEmpty().none { it.isSamePlay(r, toleranceMs) } }
        .distinctBy { it.trackId to it.playedAtMs }
        .toList()
    if (extra.isEmpty()) return stored
    return (stored + extra).sortedByDescending { it.playedAtMs }
}

/**
 * Pages back through the warehouse's plays, newest first. `before` is exclusive and plays are keyed
 * by (time, track), so each page asks for `oldest + 1` and drops what it already has: two plays
 * stored at the same millisecond can't be split across pages and lost. Not thread-safe: call
 * [loadMore] from one coroutine at a time.
 */
class PlayPager(
    /** Start from plays before this (exclusive); null for the newest. */
    private val startBeforeMs: Long? = null,
    private val pageSize: Int = 50,
    private val fetch: suspend (beforeMs: Long?, limit: Int) -> List<Play>,
) {
    var plays: List<Play> = emptyList()
        private set
    /** True once a page came back short: there's nothing older. */
    var done = false
        private set
    private var stalled = false

    /** The `before` the next [loadMore] will send. */
    val nextBeforeMs: Long?
        get() {
            val oldest = plays.lastOrNull() ?: return startBeforeMs
            return if (stalled) oldest.playedAtMs else oldest.playedAtMs + 1
        }

    /** Fetches the next page and returns the plays it added (none once [done]). */
    suspend fun loadMore(): List<Play> {
        if (done) return emptyList()
        val page = fetch(nextBeforeMs, pageSize)
        val seen = plays.mapTo(HashSet()) { it.key }
        val fresh = page.filter { seen.add(it.key) }
        plays = plays + fresh
        done = page.size < pageSize
        // A full page of plays we already had (only possible with more same-millisecond plays than a
        // page): step past that millisecond next time rather than asking for the same page forever.
        stalled = fresh.isEmpty() && !done
        return fresh
    }

    private val Play.key get() = playedAtMs to trackId
}

/**
 * The playlists, albums and artists played from, newest first, at most [want]: Home's shortcuts
 * when Spotify's 50 recent plays are all one album. Pages back at most [maxPages] pages.
 */
suspend fun recentContexts(
    want: Int,
    maxPages: Int = 4,
    pageSize: Int = 500,
    fetch: suspend (beforeMs: Long?, limit: Int) -> List<Play>,
): List<ContextRef> {
    val pager = PlayPager(pageSize = pageSize, fetch = fetch)
    val out = LinkedHashMap<String, ContextRef>()
    repeat(maxPages) {
        for (p in pager.loadMore()) {
            val uri = p.contextUri ?: continue
            // The type from the uri: `contextType` can be missing on imported plays.
            val type = uri.split(':').getOrNull(1)?.takeIf { it in HOME_CONTEXT_TYPES } ?: continue
            out.getOrPut(uri) { ContextRef(uri, type) }
            if (out.size >= want) return out.values.toList()
        }
        if (pager.done) return out.values.toList()
    }
    return out.values.toList()
}

private val HOME_CONTEXT_TYPES = setOf("playlist", "album", "artist")

/** Plays (newest first) in runs of the same local day, newest day first. */
fun groupByDay(plays: List<Play>, zone: ZoneId): List<Pair<LocalDate, List<Play>>> {
    val out = mutableListOf<Pair<LocalDate, MutableList<Play>>>()
    for (p in plays) {
        val day = Instant.ofEpochMilli(p.playedAtMs).atZone(zone).toLocalDate()
        if (out.lastOrNull()?.first == day) out.last().second += p else out += day to mutableListOf(p)
    }
    return out
}

/** Epoch ms of the local midnight that ends [day]: `before` for a History that starts on [day]. */
fun endOfDayMs(day: LocalDate, zone: ZoneId): Long = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
