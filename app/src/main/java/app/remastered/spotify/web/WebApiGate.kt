package app.remastered.spotify.web

import android.content.Context
import app.remastered.core.CatalogException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The one way out to the Web API: every request [WebCatalog] sends goes through [withPermit].
 *
 * Spotify counts a Development Mode app's requests per Spotify user, across all of that user's
 * apps, so a burst from here can stop the user's music-warehouse poller for hours (a 429
 * `QUOTA_EXCEEDED` with a Retry-After of about 9 hours, after a cold start fetched everything).
 * So:
 * - **One pause for everything.** A response with `Retry-After` (any 429, or a 5xx that says)
 *   pauses every request until then, not just that endpoint. The deadline is wall-clock time in
 *   [store], so it outlives the process and a restart. A pause of up to [waitUpToMs] is waited
 *   out; a longer one fails each request at once, without sending it.
 * - **A budget.** At most [maxInFlight] requests at a time, and a token bucket of [burst]
 *   requests refilling at [perWindow] per [windowMs], so even a start that wants a lot can't
 *   burst.
 *
 * App Remote (playback, controls) doesn't go through here and keeps working while paused.
 * [now] and [sleep] are injectable for tests.
 */
class WebApiGate(
    private val store: Store = InMemory(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    maxInFlight: Int = 3,
    private val burst: Int = 10,
    private val perWindow: Int = 20,
    private val windowMs: Long = 30_000,
    private val waitUpToMs: Long = 30_000,
) {
    /** Where the pause's deadline is kept. [load] is null when there's none. */
    interface Store {
        fun load(): Long?
        fun save(until: Long?)
    }

    class InMemory(private var until: Long? = null) : Store {
        override fun load() = until
        override fun save(until: Long?) { this.until = until }
    }

    private val inFlight = Semaphore(maxInFlight)
    private val bucket = Mutex()
    private var tokens = burst.toDouble()
    private var refilledAt = now()

    // A deadline further out than a day is a clock change or a bad header, not Spotify: cap it.
    private val _pausedUntil = MutableStateFlow(store.load().let { saved ->
        saved?.takeIf { it > now() }?.coerceAtMost(now() + MAX_PAUSE_MS).also { if (it != saved) store.save(it) }
    })
    /** Wall-clock ms until which nothing is sent, or null (or past) when requests can go. */
    val pausedUntil: StateFlow<Long?> = _pausedUntil.asStateFlow()

    /** How long requests are still paused for, or null if they aren't. */
    fun remainingMs(): Long? = _pausedUntil.value?.let { it - now() }?.takeIf { it > 0 }

    /** Pauses every request for [ms] (from a `Retry-After`); never shortens a longer pause. */
    @Synchronized fun pause(ms: Long) {
        val until = now() + ms.coerceIn(0, MAX_PAUSE_MS)
        if (until <= (_pausedUntil.value ?: 0)) return
        _pausedUntil.value = until
        store.save(until)
    }

    /**
     * Runs [request] (one HTTP call) once there's room: waits for a slot and a token, and out a
     * short pause. Throws a 429 [CatalogException] without running it while a long pause holds.
     */
    suspend fun <T> withPermit(request: suspend () -> T): T {
        // Fail at once rather than queue behind the requests in flight.
        remainingMs()?.takeIf { it > waitUpToMs }?.let { throw paused(it) }
        return inFlight.withPermit { await(); request() }
    }

    private suspend fun await() {
        while (true) {
            val paused = remainingMs()
            if (paused != null) {
                if (paused > waitUpToMs) throw paused(paused)
                sleep(paused)
                continue
            }
            val wait = takeToken()
            if (wait == 0L) return
            sleep(wait)
        }
    }

    /** Takes a token and returns 0, or returns how long until one is due. */
    private suspend fun takeToken(): Long = bucket.withLock {
        val t = now()
        tokens = (tokens + (t - refilledAt).coerceAtLeast(0) * perWindow / windowMs.toDouble()).coerceAtMost(burst.toDouble())
        refilledAt = t
        if (tokens >= 1) {
            tokens -= 1
            0L
        } else {
            ((1 - tokens) * windowMs / perWindow).toLong().coerceAtLeast(1)
        }
    }

    private fun paused(ms: Long) = CatalogException("Spotify 429: rate limited", 429, PAUSED, retryAfterMs = ms)

    companion object {
        /** [CatalogException.reason] for a request this app didn't send because of a pause. */
        const val PAUSED = "PAUSED"
        const val MAX_PAUSE_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * SharedPreferences file `spotify_rate_limits`: the pause's deadline under `until`. Earlier
 * versions kept one deadline per endpoint; the latest of those becomes the pause.
 */
class PrefsGateStore(context: Context) : WebApiGate.Store {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Long? = prefs.all.values.mapNotNull { it as? Long }.maxOrNull()

    override fun save(until: Long?) {
        prefs.edit().clear().apply { until?.let { putLong(KEY, it) } }.apply()
    }

    private companion object {
        const val FILE = "spotify_rate_limits"
        const val KEY = "until"
    }
}
