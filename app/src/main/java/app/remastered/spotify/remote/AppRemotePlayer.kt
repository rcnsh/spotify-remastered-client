package app.remastered.spotify.remote

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.remastered.core.Connection
import app.remastered.core.ContentItem
import app.remastered.core.ContentPage
import app.remastered.core.NowPlaying
import app.remastered.core.OptimisticPlayback
import app.remastered.core.PlaybackState
import app.remastered.core.Player
import app.remastered.core.PlayingContext
import app.remastered.core.RemoteContent
import app.remastered.core.RepeatMode
import app.remastered.core.model.ArtistRef
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.spotify.protocol.types.PlayerContext as RemoteContext
import com.spotify.protocol.types.PlayerState as RemoteState

/**
 * [Player] over Spotify's App Remote SDK: commands and state go to the installed Spotify
 * app's background service on this phone, never over the network.
 *
 * App Remote calls back on the main thread. Every command is applied to [OptimisticPlayback]
 * first, so the UI moves at once; Spotify's next state event confirms or corrects it.
 */
class AppRemotePlayer(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : Player, RemoteContent {
    private val optimistic = OptimisticPlayback(clock)
    override val state: StateFlow<PlaybackState?> = optimistic.state
    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    override val connection: StateFlow<Connection> = _connection.asStateFlow()

    private var remote: SpotifyAppRemote? = null
    private var subscriptions = emptyList<Subscription<*>>()
    private var ticker: Job? = null
    private var lastState: RemoteState? = null
    private var lastContext: RemoteContext? = null
    private var liked: Pair<String, Boolean>? = null

    override fun connect() {
        if (_connection.value == Connection.Connecting || remote?.isConnected == true) return
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            _connection.value = Connection.SpotifyMissing
            return
        }
        _connection.value = Connection.Connecting
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _connection.value = Connection.Connected
                subscribe(appRemote)
            }

            override fun onFailure(error: Throwable) {
                remote = null
                Log.w(TAG, "App Remote connection failed: ${error.javaClass.simpleName}")
                _connection.value = when (error) {
                    is CouldNotFindSpotifyApp -> Connection.SpotifyMissing
                    is UserNotAuthorizedException -> Connection.NeedsAuth
                    is NotLoggedInException -> Connection.Failed("Log in to the Spotify app first")
                    is OfflineModeException -> Connection.Failed("Spotify is in offline mode")
                    else -> Connection.Failed(error.message ?: error.javaClass.simpleName)
                }
            }
        })
    }

    override fun disconnect() {
        subscriptions.forEach { it.cancel() }
        subscriptions = emptyList()
        ticker?.cancel()
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        _connection.value = Connection.Idle
    }

    private fun subscribe(appRemote: SpotifyAppRemote) {
        val player = appRemote.playerApi
        subscriptions = listOf(
            player.subscribeToPlayerState().setEventCallback { s ->
                if (s.track?.uri != lastState?.track?.uri) refreshLiked(s.track?.uri)
                lastState = s
                report()
            },
            player.subscribeToPlayerContext().setEventCallback { c ->
                lastContext = c
                report()
            },
        )
        subscriptions.forEach { sub ->
            sub.setErrorCallback { e ->
                Log.w(TAG, "App Remote subscription ended: ${e.javaClass.simpleName}")
                remote = null
                _connection.value = Connection.Idle
            }
        }
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { delay(500); optimistic.tick() } }
    }

    private fun refreshLiked(uri: String?) {
        if (uri == null) return
        remote?.userApi?.getLibraryState(uri)?.setResultCallback { state ->
            liked = state.uri to state.isAdded
            report()
        }
    }

    private fun report() {
        val s = lastState ?: return
        val t = s.track
        val ctx = lastContext?.takeIf { !it.uri.isNullOrEmpty() }
        optimistic.onRemote(PlaybackState(
            track = t?.let { track ->
                NowPlaying(
                    uri = track.uri,
                    name = track.name,
                    artists = track.artists.orEmpty().map { a -> ArtistRef(a.uri.substringAfterLast(':'), a.name) },
                    albumName = track.album?.name.orEmpty(),
                    albumUri = track.album?.uri,
                    imageUrl = track.imageUri?.raw?.let { raw -> IMAGE_SCHEME + raw },
                    durationMs = track.duration,
                    isEpisode = track.isEpisode || track.isPodcast,
                    liked = liked?.takeIf { it.first == track.uri }?.second ?: false,
                )
            },
            isPaused = s.isPaused,
            positionMs = s.playbackPosition,
            positionAtMs = clock(),
            speed = if (s.isPaused) 0f else s.playbackSpeed.takeIf { it > 0f } ?: 1f,
            shuffle = s.playbackOptions?.isShuffling ?: false,
            repeat = when (s.playbackOptions?.repeatMode) {
                Repeat.ONE -> RepeatMode.One
                Repeat.ALL -> RepeatMode.All
                else -> RepeatMode.Off
            },
            canSeek = s.playbackRestrictions?.canSeek ?: true,
            canSkipNext = s.playbackRestrictions?.canSkipNext ?: true,
            canSkipPrevious = s.playbackRestrictions?.canSkipPrev ?: true,
            context = ctx?.let { PlayingContext(it.uri, it.title.orEmpty(), it.subtitle.orEmpty(), it.type.orEmpty()) },
        ))
    }

    private inline fun command(block: (SpotifyAppRemote) -> Unit) {
        val r = remote
        if (r == null || !r.isConnected) {
            connect()
            return
        }
        try {
            block(r)
        } catch (e: RuntimeException) {
            Log.w(TAG, "App Remote command failed: ${e.javaClass.simpleName}")
        }
    }

    override fun play(uri: String) { optimistic.setPaused(false); command { it.playerApi.play(uri) } }
    override fun playAt(contextUri: String, index: Int) {
        optimistic.setPaused(false)
        command { it.playerApi.skipToIndex(contextUri, index) }
    }
    override fun pause() { optimistic.setPaused(true); command { it.playerApi.pause() } }
    override fun resume() { optimistic.setPaused(false); command { it.playerApi.resume() } }
    override fun seekTo(positionMs: Long) { optimistic.seekTo(positionMs); command { it.playerApi.seekTo(positionMs) } }
    override fun next() = command { it.playerApi.skipNext() }
    override fun previous() = command { it.playerApi.skipPrevious() }
    override fun setShuffle(on: Boolean) { optimistic.setShuffle(on); command { it.playerApi.setShuffle(on) } }
    override fun setRepeat(mode: RepeatMode) {
        optimistic.setRepeat(mode)
        command { it.playerApi.setRepeat(when (mode) { RepeatMode.Off -> Repeat.OFF; RepeatMode.All -> Repeat.ALL; RepeatMode.One -> Repeat.ONE }) }
    }
    override fun queue(uri: String) = command { it.playerApi.queue(uri) }

    override fun setLiked(uri: String, liked: Boolean) {
        optimistic.setLiked(uri, liked)
        command { r ->
            val call = if (liked) r.userApi.addToLibrary(uri) else r.userApi.removeFromLibrary(uri)
            call.setResultCallback { this.liked = uri to liked; report() }
        }
    }

    /** Artwork for an `appremote:` image id, straight from the Spotify app (no Web API call). */
    suspend fun image(raw: String, large: Boolean): Bitmap? {
        val r = remote?.takeIf { it.isConnected } ?: return null
        return suspendCancellableCoroutine { cont ->
            val call = r.imagesApi.getImage(ImageUri(raw), if (large) Image.Dimension.LARGE else Image.Dimension.MEDIUM)
            call.setResultCallback { bmp -> if (cont.isActive) cont.resume(bmp) }
            call.setErrorCallback { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    /** ContentApi's `getChildrenOfItem` with a bare item for [uri]: a read, nothing plays. */
    override suspend fun children(uri: String, limit: Int, offset: Int): ContentPage? {
        val r = remote?.takeIf { it.isConnected } ?: return null
        return suspendCancellableCoroutine { cont ->
            val call = r.contentApi.getChildrenOfItem(ListItem(uri, uri, null, null, null, false, true), limit, offset)
            call.setResultCallback { page ->
                val items = page.items.orEmpty().map {
                    ContentItem(it.uri, it.title.orEmpty(), it.subtitle.orEmpty(), it.imageUri?.raw?.let { raw -> IMAGE_SCHEME + raw }, it.playable)
                }
                if (cont.isActive) cont.resume(ContentPage(page.total, items))
            }
            call.setErrorCallback { e -> if (cont.isActive) cont.resumeWith(Result.failure(e)) }
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    companion object {
        const val IMAGE_SCHEME = "appremote:"
        private const val TAG = "AppRemotePlayer"
    }
}
