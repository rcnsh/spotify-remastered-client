package app.remastered

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.remastered.core.AppSettings
import app.remastered.core.Catalog
import app.remastered.core.ContextTiles
import app.remastered.core.LikedIndex
import app.remastered.core.LikedSongsFile
import app.remastered.core.Player
import app.remastered.core.RecentSearches
import app.remastered.core.ScreenCache
import app.remastered.core.lyrics.CurrentLyrics
import app.remastered.core.lyrics.LyricsSource
import app.remastered.media.NowPlayingNotification
import app.remastered.spicylyrics.LyricsCache
import app.remastered.spicylyrics.SpicyLyrics
import app.remastered.spicylyrics.SpicyLyricsApi
import app.remastered.warehouse.KeystoreWarehouseConfigStore
import app.remastered.warehouse.PrefsPendingSignInStore
import app.remastered.warehouse.WarehouseServices
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Wires the app together. Resetting the app ([RemasteredApp.restart]) builds a new container;
 * screens get everything from here.
 */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: () -> Long = SystemClock::elapsedRealtime

    val settings = AppSettings(context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE))
    val spotify = SpotifyServices(context, scope, clock, settings)
    val catalog: Catalog = spotify.catalog
    val player: Player = spotify.player

    /** Remastered's own media notification while something plays (Settings → Playback). */
    val nowPlaying = NowPlayingNotification(context, player, settings.mediaNotification.value, scope)

    /**
     * Liked Songs, saved between runs so a start only fetches what's new. In files, not the cache:
     * Android clearing the cache would cost a full reload (60 requests for 3,000 songs).
     */
    val liked = LikedIndex(catalog, scope, LikedSongsFile(File(context.filesDir, LIKED_SONGS), legacy = File(context.cacheDir, LIKED_SONGS)))
    /** Home's playlist, album and artist tiles, so a start doesn't look them all up again. */
    val tiles = ContextTiles(File(context.filesDir, HOME_TILES))
    /** What each screen loaded last, so switching between screens doesn't show a spinner every time. */
    val screens = ScreenCache(clock, dir = File(context.filesDir, SCREENS))
    val recentSearches = RecentSearches(context.getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)) { settings.saveRecentSearches.on }

    /** Spicy Lyrics responses, at most 30 days old (their terms). */
    val lyricsCache = LyricsCache(File(context.cacheDir, "lyrics"))
    /** Requests show up in Settings → Web API requests (path and status only, never the key). */
    val lyricsSource: LyricsSource = SpicyLyrics(
        SpicyLyricsApi(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).addInterceptor(spotify.requests).build(), BuildConfig.SPICY_LYRICS_KEY),
        lyricsCache,
    )
    val currentLyrics = CurrentLyrics(player, lyricsSource, settings.lyrics.value, scope)

    /**
     * Listening history from the user's music-warehouse (Settings → Listening history). Features
     * built on it check [WarehouseServices.state] and hide themselves when it isn't usable.
     * Requests show up in Settings → Web API requests (path and query only, never the token).
     */
    val warehouse = WarehouseServices(
        KeystoreWarehouseConfigStore(context),
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).addInterceptor(spotify.requests).build(),
        defaults = WarehouseServices.defaults(BuildConfig.WAREHOUSE_URL, BuildConfig.WAREHOUSE_TOKEN),
        pendingSignIn = PrefsPendingSignInStore(context),
    )

    init {
        scope.launch(Dispatchers.IO) { lyricsCache.sweep() }
        if (warehouse.isConfigured) scope.launch { warehouse.check() }
    }

    fun close() {
        nowPlaying.stop()
        player.disconnect()
        scope.cancel()
    }

    companion object {
        const val PREFS_SETTINGS = "settings"
        const val PREFS_SEARCH = "search"
        const val LIKED_SONGS = "liked-songs.json"
        const val HOME_TILES = "home-tiles.json"
        const val SCREENS = "screens"

        /** Emulators can't run a logged-in Spotify app (Play Integrity), so App Remote can't connect there. */
        val isEmulator: Boolean
            get() = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone") ||
                Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
    }
}
