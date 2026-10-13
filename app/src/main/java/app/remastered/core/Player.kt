package app.remastered.core

import app.remastered.core.model.ArtistRef
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback, driven through the installed Spotify app (App Remote). Implemented by
 * [app.remastered.spotify.remote.AppRemotePlayer].
 *
 * Commands return immediately: [state] reflects them at once (optimistically) and is
 * reconciled with what Spotify reports (see [OptimisticPlayback]). Commands never throw: a
 * failed command shows up as the state settling back to what Spotify reports.
 */
interface Player {
    val state: StateFlow<PlaybackState?>
    val connection: StateFlow<Connection>

    /** Connects (and on first use, asks the user to authorise). Safe to call repeatedly. */
    fun connect()
    fun disconnect()

    /** Plays a track, album, playlist or artist uri. */
    fun play(uri: String)
    /** Plays [contextUri] (album or playlist) starting at [index]. */
    fun playAt(contextUri: String, index: Int)
    fun pause()
    fun resume()
    fun togglePlay() = if (state.value?.isPaused != false) resume() else pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun queue(uri: String)
    fun setLiked(uri: String, liked: Boolean)
}

enum class RepeatMode {
    Off, All, One;

    fun next() = when (this) { Off -> All; All -> One; One -> Off }
}

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    /** Spotify isn't installed on this phone. */
    data object SpotifyMissing : Connection
    /** The user hasn't authorised the app yet, or declined. */
    data object NeedsAuth : Connection
    data class Failed(val message: String) : Connection
}

data class NowPlaying(
    val uri: String,
    val name: String,
    val artists: List<ArtistRef>,
    val albumName: String,
    val albumUri: String?,
    /** Artwork: an https url, or an App Remote image id (`appremote:` + raw). */
    val imageUrl: String?,
    val durationMs: Long,
    val isEpisode: Boolean = false,
    val liked: Boolean = false,
) {
    val artistNames get() = artists.joinToString(", ") { it.name }
}

data class PlayingContext(val uri: String, val title: String, val subtitle: String, val type: String)

data class PlaybackState(
    val track: NowPlaying?,
    val isPaused: Boolean,
    /** Position at [positionAtMs] (a monotonic clock, `SystemClock.elapsedRealtime()` on Android). */
    val positionMs: Long,
    val positionAtMs: Long,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val canSeek: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSkipPrevious: Boolean = true,
    val context: PlayingContext? = null,
) {
    /** The position now, extrapolated while playing so a progress bar can animate locally. */
    fun positionAt(nowMs: Long): Long {
        val duration = track?.durationMs ?: return 0
        if (isPaused) return positionMs.coerceIn(0, duration)
        return (positionMs + ((nowMs - positionAtMs) * speed).toLong()).coerceIn(0, duration)
    }
}
