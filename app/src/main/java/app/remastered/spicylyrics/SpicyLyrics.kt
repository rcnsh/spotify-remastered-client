package app.remastered.spicylyrics

import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LyricsSource] over the Spicy Lyrics API, cached in [cache]. One request at a time: the lyrics
 * card and the lyrics screen ask for the same song together, and the second gets the cached copy.
 * After a 429 or 503 nothing is sent until its `Retry-After` has passed. [now] is wall-clock time.
 */
class SpicyLyrics(
    private val api: SpicyLyricsApi,
    private val cache: LyricsCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsSource {
    private val mutex = Mutex()
    private var blockedUntil = 0L

    override suspend fun lyrics(trackUri: String): LyricsResult {
        val id = trackUri.removePrefix(TRACK_PREFIX)
        if (!trackUri.startsWith(TRACK_PREFIX) || !SpicyLyricsApi.isTrackId(id)) return LyricsResult.Unsupported
        if (!api.isConfigured) return LyricsResult.NotConfigured
        return mutex.withLock {
            cached(id) ?: fetch(id)
        }
    }

    private suspend fun cached(id: String): LyricsResult? = withContext(io) {
        when (val entry = cache.get(id)) {
            null -> null
            LyricsCache.Entry.NotFound -> LyricsResult.NotFound
            is LyricsCache.Entry.Found -> parseLyrics(entry.json, id)?.let(LyricsResult::Found) ?: run { cache.remove(id); null }
        }
    }

    private suspend fun fetch(id: String): LyricsResult {
        val wait = blockedUntil - now()
        if (wait > 0) return LyricsResult.Failed(LyricsResult.Problem.RateLimited, wait)
        return when (val r = api.fetch(id)) {
            is SpicyLyricsApi.Response.Body -> {
                val lyrics = parseLyrics(r.json, id) ?: return LyricsResult.Failed(LyricsResult.Problem.BadResponse)
                withContext(io) { cache.put(id, r.json) }
                LyricsResult.Found(lyrics)
            }
            SpicyLyricsApi.Response.NotFound -> {
                withContext(io) { cache.putNotFound(id) }
                LyricsResult.NotFound
            }
            is SpicyLyricsApi.Response.Failed -> {
                r.retryAfterMs?.let { blockedUntil = now() + it }
                LyricsResult.Failed(r.problem, r.retryAfterMs)
            }
        }
    }

    private companion object { const val TRACK_PREFIX = "spotify:track:" }
}
