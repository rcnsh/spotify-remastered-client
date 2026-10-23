package app.remastered.spicylyrics

import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One way to reach Spicy Lyrics' `GET /v1/lyrics/{trackId}` (ADR 0005): through the user's
 * warehouse, which holds the key, or directly with a key built into the app ([SpicyLyricsApi]).
 * Both answer with Spicy Lyrics' own body, so the parsing, cache and credit are the same.
 */
interface LyricsEndpoint {
    enum class Via { Warehouse, Direct }

    val via: Via
    suspend fun fetch(trackId: String): SpicyLyricsApi.Response
}

/**
 * [LyricsSource] over the Spicy Lyrics API, cached in [cache]. [endpoints] lists the ways to reach
 * it now, best first (the warehouse, then a built-in key); none means lyrics aren't set up. One
 * that doesn't serve lyrics after all (an older warehouse) passes the request to the next.
 *
 * One request at a time: the lyrics card and the lyrics screen ask for the same song together, and
 * the second gets the cached copy. After a 429 or 503 nothing is sent until its `Retry-After` has
 * passed. [now] is wall-clock time.
 */
class SpicyLyrics(
    private val endpoints: () -> List<LyricsEndpoint>,
    private val cache: LyricsCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsSource {
    /** Spicy Lyrics directly, with [api]'s key; not set up when it has none. */
    constructor(api: SpicyLyricsApi, cache: LyricsCache, now: () -> Long = System::currentTimeMillis, io: CoroutineDispatcher = Dispatchers.IO) :
        this({ listOfNotNull(api.takeIf { it.isConfigured }) }, cache, now, io)

    private val mutex = Mutex()
    private var blockedUntil = 0L

    /** Where the next song's lyrics would come from, for Settings; null when lyrics aren't set up. */
    fun via(): LyricsEndpoint.Via? = endpoints().firstOrNull()?.via

    override suspend fun lyrics(trackUri: String): LyricsResult {
        val id = trackUri.removePrefix(TRACK_PREFIX)
        if (!trackUri.startsWith(TRACK_PREFIX) || !SpicyLyricsApi.isTrackId(id)) return LyricsResult.Unsupported
        val endpoints = endpoints()
        if (endpoints.isEmpty()) return LyricsResult.NotConfigured
        return mutex.withLock {
            cached(id) ?: fetch(id, endpoints)
        }
    }

    private suspend fun cached(id: String): LyricsResult? = withContext(io) {
        when (val entry = cache.get(id)) {
            null -> null
            LyricsCache.Entry.NotFound -> LyricsResult.NotFound
            is LyricsCache.Entry.Found -> parseLyrics(entry.json, id)?.let(LyricsResult::Found) ?: run { cache.remove(id); null }
        }
    }

    private suspend fun fetch(id: String, endpoints: List<LyricsEndpoint>): LyricsResult {
        val wait = blockedUntil - now()
        if (wait > 0) return LyricsResult.Failed(LyricsResult.Problem.RateLimited, wait)
        var offline: LyricsResult? = null
        for (endpoint in endpoints) {
            when (val r = endpoint.fetch(id)) {
                is SpicyLyricsApi.Response.Body -> {
                    val lyrics = parseLyrics(r.json, id) ?: return LyricsResult.Failed(LyricsResult.Problem.BadResponse)
                    withContext(io) { cache.put(id, r.json, r.ageMs) }
                    return LyricsResult.Found(lyrics)
                }
                is SpicyLyricsApi.Response.NotFound -> {
                    withContext(io) { cache.putNotFound(id, r.ageMs) }
                    return LyricsResult.NotFound
                }
                SpicyLyricsApi.Response.Unavailable -> continue
                is SpicyLyricsApi.Response.Failed -> {
                    // A warehouse that can't be reached needn't stop a built-in key that can.
                    if (r.problem == LyricsResult.Problem.Offline) { offline = LyricsResult.Failed(r.problem); continue }
                    r.retryAfterMs?.let { blockedUntil = now() + it }
                    return LyricsResult.Failed(r.problem, r.retryAfterMs)
                }
            }
        }
        return offline ?: LyricsResult.NotConfigured
    }

    private companion object { const val TRACK_PREFIX = "spotify:track:" }
}
