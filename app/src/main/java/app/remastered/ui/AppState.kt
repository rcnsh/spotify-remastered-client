package app.remastered.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.remastered.AppContainer
import app.remastered.core.CatalogException
import app.remastered.core.model.AlbumRef
import app.remastered.core.model.Image
import app.remastered.core.model.Playlist
import app.remastered.core.model.Track
import app.remastered.core.model.UserRef
import app.remastered.core.waitText
import app.remastered.core.warehouse.WarehouseException
import kotlinx.coroutines.launch

/** A song whose menu is open, and the playlist it was opened from (for "Remove from …"). */
data class TrackMenu(val track: Track, val playlist: Playlist? = null)

/**
 * UI state shared across screens (navigation, overlays, sheets) and the actions screens
 * call. Lives as long as the activity's composition.
 */
@Stable
class AppState(val c: AppContainer, private val context: Context) {
    val nav = Navigator()
    val snackbar = SnackbarHostState()

    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)
    /** A track uri whose lyrics are previewed on a local clock (Settings → Developer). */
    var lyricsPreview by mutableStateOf<String?>(null)
    var devicesOpen by mutableStateOf(false)
    var menu by mutableStateOf<TrackMenu?>(null)
    var addToPlaylist by mutableStateOf<Track?>(null)
    var me by mutableStateOf<UserRef?>(null)
        private set

    /** Bumped when a playlist changes, so open playlist screens reload. */
    var playlistsVersion by mutableStateOf(0)
        private set

    fun playlistsChanged() { playlistsVersion++ }

    fun start() {
        if (c.settings.autoConnect.on) c.player.connect()
        if (c.settings.preloadLiked.on) c.liked.load()
        reloadProfile()
    }

    fun reloadProfile() { c.scope.launch { me = runCatching { c.catalog.me() }.getOrNull() } }

    fun open(route: Route) {
        nowPlayingOpen = false
        queueOpen = false
        lyricsOpen = false
        lyricsPreview = null
        nav.push(route)
    }

    fun openUri(uri: String?) { uri?.let(Route::of)?.let(::open) }

    /** Whether [back] would do something; when false the system handles Back (leaves the app). */
    val canGoBack: Boolean
        get() = menu != null || addToPlaylist != null || devicesOpen || lyricsPreview != null || lyricsOpen || queueOpen || nowPlayingOpen ||
            nav.depth > 1 || nav.tab != Tab.Home

    fun back(): Boolean = when {
        menu != null -> { menu = null; true }
        addToPlaylist != null -> { addToPlaylist = null; true }
        devicesOpen -> { devicesOpen = false; true }
        lyricsPreview != null -> { lyricsPreview = null; true }
        lyricsOpen -> { lyricsOpen = false; true }
        queueOpen -> { queueOpen = false; true }
        nowPlayingOpen -> { nowPlayingOpen = false; true }
        else -> nav.pop()
    }

    fun trackMenu(track: Track, playlist: Playlist? = null) = TrackMenu(track, playlist)

    /** Opens the song menu for what's playing, building a [Track] from App Remote's view of it. */
    fun menuForNowPlaying() {
        val t = c.player.state.value?.track ?: return
        menu = TrackMenu(Track(
            id = t.uri.substringAfterLast(':'), name = t.name, artists = t.artists,
            album = t.albumUri?.let { AlbumRef(it.substringAfterLast(':'), t.albumName, listOfNotNull(t.imageUrl?.let(::Image))) },
            durationMs = t.durationMs, isEpisode = t.isEpisode,
        ))
    }

    fun playAt(contextUri: String, index: Int) = c.player.playAt(contextUri, index)
    fun play(uri: String) = c.player.play(uri)

    fun isLiked(uri: String) = uri in c.liked.uris.value

    fun setLiked(uri: String, liked: Boolean, track: Track? = null) {
        c.player.setLiked(uri, liked)
        c.liked.set(track, uri, liked)
        message(if (liked) "Added to Liked Songs" else "Removed from Liked Songs")
    }

    fun queue(track: Track) {
        c.player.queue(track.uri)
        message("Added to queue")
    }

    /** Saves or removes an album, artist (follow) or playlist (follow) by uri. */
    fun setSaved(uri: String, saved: Boolean, done: (Boolean) -> Unit = {}) = launch {
        if (saved) c.catalog.saveToLibrary(listOf(uri)) else c.catalog.removeFromLibrary(listOf(uri))
        done(saved)
        message(if (saved) "Added to Your Library" else "Removed from Your Library")
    }

    fun addTo(playlist: Playlist, track: Track) = launch {
        c.catalog.addToPlaylist(playlist.id, listOf(track.uri))
        playlistsVersion++
        message("Added to ${playlist.name}")
    }

    fun removeFrom(playlist: Playlist, track: Track) = launch {
        c.catalog.removeFromPlaylist(playlist.id, listOf(track.uri), playlist.snapshotId)
        playlistsVersion++
        message("Removed from ${playlist.name}")
    }

    fun share(uri: String, title: String) {
        val parts = uri.split(':')
        val url = if (parts.size >= 3) "https://open.spotify.com/${parts[1]}/${parts[2]}" else return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_TITLE, title)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun message(text: String) { c.scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }

    /** Runs a catalog or warehouse call, turning failures into a message instead of a crash. */
    fun launch(block: suspend () -> Unit) = c.scope.launch {
        try { block() } catch (e: CatalogException) { message(describe(e)) } catch (e: WarehouseException) { message(e.userMessage) }
    }

    companion object {
        fun describe(e: Throwable): String = when {
            e is WarehouseException -> e.userMessage
            e is CatalogException && e.isRateLimited ->
                "Spotify is limiting requests. Try again in ${e.retryAfterMs?.let(::waitText) ?: "a minute"}."
            e is CatalogException && e.isAuth -> "Sign in to Spotify again."
            e is CatalogException && e.isForbidden -> "Spotify doesn't allow that for this app."
            e is CatalogException && e.status == null -> "Can't reach Spotify. Check your connection."
            else -> "Something went wrong."
        }
    }
}
