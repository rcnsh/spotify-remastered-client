package app.remastered.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's and developer's switches from Settings, kept in SharedPreferences `settings`. */
class AppSettings(private val prefs: SharedPreferences) {
    inner class Flag internal constructor(private val key: String, default: Boolean) {
        private val _value = MutableStateFlow(prefs.getBoolean(key, default))
        val value: StateFlow<Boolean> = _value.asStateFlow()
        val on get() = _value.value

        fun set(on: Boolean) {
            _value.value = on
            prefs.edit().putBoolean(key, on).apply()
        }
    }

    /** Remember what was opened from search. Off also stops new entries being added. */
    val saveRecentSearches = Flag("save_recent_searches", true)
    /** Reconnect to the Spotify app (App Remote) when Remastered starts. */
    val autoConnect = Flag("auto_connect", true)
    /** Load all Liked Songs in the background at start (for ♥ marks and the artist page). */
    val preloadLiked = Flag("preload_liked", true)

    inner class Number internal constructor(private val key: String, private val default: Int) {
        private val _value = MutableStateFlow(prefs.getInt(key, default))
        val value: StateFlow<Int> = _value.asStateFlow()
        val current get() = _value.value

        fun set(value: Int) {
            _value.value = value
            prefs.edit().putInt(key, value).apply()
        }

        fun reset() = set(default)
    }

    // Lyrics
    /** Synced lyrics on Now Playing (Spicy Lyrics API). */
    val lyrics = Flag("lyrics", true)
    /** Show romanization and translations under lyrics when the source has them. */
    val lyricsExtras = Flag("lyrics_extras", true)
    /** Added to the playback position before matching lyrics: positive shows lines earlier. */
    val lyricsOffsetMs = Number("lyrics_offset_ms", 0)

    // Developer
    /** Write one logcat line per Web API request (method, path, status, time; never headers or bodies). */
    val logRequests = Flag("dev_log_requests", false)
    /** Show raw uris and ids under titles in song menus. */
    val showUris = Flag("dev_show_uris", false)
}
