package app.remastered.spotify.web

import android.content.Context

/**
 * Web API endpoints Spotify has rate limited, and until when (wall-clock ms). [WebCatalog]
 * sends nothing to an endpoint until its `Retry-After` has passed, so opening screens and
 * restarting the app don't keep hitting a limit that can last hours. Kept in [store], so a
 * reinstall doesn't forget it. An endpoint is a request path, like `/v1/me/tracks`.
 */
class RateLimits(private val store: Store = InMemory(), private val now: () -> Long = System::currentTimeMillis) {
    interface Store {
        fun load(): Map<String, Long>
        fun save(until: Map<String, Long>)
    }

    class InMemory : Store {
        private var until = emptyMap<String, Long>()
        override fun load() = until
        override fun save(until: Map<String, Long>) { this.until = until }
    }

    private val until = store.load().toMutableMap()

    /** How long [endpoint] is still limited for, or null if it isn't. */
    @Synchronized fun remainingMs(endpoint: String): Long? = until[endpoint]?.let { it - now() }?.takeIf { it > 0 }

    /** Records that Spotify asked for no requests to [endpoint] for [ms]. */
    @Synchronized fun limit(endpoint: String, ms: Long) {
        val t = now()
        until.values.removeAll { it <= t }
        until[endpoint] = t + ms
        store.save(until.toMap())
    }
}

/** SharedPreferences file `spotify_rate_limits`: one long per endpoint. */
class PrefsRateLimitStore(context: Context) : RateLimits.Store {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    override fun save(until: Map<String, Long>) {
        prefs.edit().clear().apply { until.forEach { (k, v) -> putLong(k, v) } }.apply()
    }

    private companion object {
        const val FILE = "spotify_rate_limits"
    }
}
