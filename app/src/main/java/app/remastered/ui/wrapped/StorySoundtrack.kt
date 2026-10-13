package app.remastered.ui.wrapped

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.remastered.core.Catalog
import app.remastered.core.Connection
import app.remastered.core.PlaybackState
import app.remastered.core.Player
import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsSource
import app.remastered.core.model.SearchType
import app.remastered.core.model.Track
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.SongCue
import app.remastered.core.wrapped.WrappedSoundtrack
import app.remastered.core.wrapped.WrappedTimeline
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays each music slide's song through the Spotify app while the story is viewed (ADR 0006),
 * starting at its hook (WrappedSoundtrack.hookMs). Exports never carry this audio.
 *
 * Best effort and never in the story's way: nothing here blocks a frame, and if App Remote isn't
 * connected or a song can't be found the story just plays silently. On [restore] the user's own
 * playback comes back: the track they had, at the position they left it (its playlist or album
 * context can't be restored through App Remote, so it resumes as a single track), or a pause if
 * they weren't playing.
 */
@Stable
class StorySoundtrack(
    private val player: Player,
    private val catalog: Catalog,
    private val lyrics: LyricsSource,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val data: Wrapped,
    private val timeline: WrappedTimeline,
) {
    var muted by mutableStateOf(false)
        private set

    /** Whether App Remote can play right now (the mute toggle shows only then). */
    val available get() = player.connection.value == Connection.Connected

    private val before: PlaybackState? = player.state.value
    private val tracks = HashMap<String, kotlinx.coroutines.Deferred<Track?>>()
    private val hooks = HashMap<String, kotlinx.coroutines.Deferred<Long>>()
    private var playing: SongCue? = null
    private var started = false
    private var job: Job? = null
    private var index = 0
    private var holding = false

    /** Looks up every cue's track and hook up front, so a slide change only has to press play. */
    fun prefetch() {
        if (!available) return
        timeline.slides.mapNotNull { WrappedSoundtrack.cue(it.id, data) }.distinctBy { it.key }.forEach { cue ->
            scope.launch { track(cue)?.let { hook(it) } }
        }
    }

    /** The story moved to the slide at [index]: cue its song if it has a different one. */
    fun onSlide(index: Int) {
        this.index = index
        if (muted || !available) return
        val cue = WrappedSoundtrack.cueAt(timeline, index, data) ?: return
        if (cue.key == playing?.key) return
        playing = cue
        job?.cancel()
        job = scope.launch {
            val track = track(cue) ?: return@launch
            val start = hook(track)
            player.play(track.uri)
            started = true
            // Seek once Spotify has switched, or the seek lands on the previous song.
            withTimeoutOrNull(4_000) { player.state.first { it?.track?.uri == track.uri } }
            player.seekTo(start)
            if (holding) player.pause()
        }
    }

    /** Held: pause the song too. */
    fun hold() {
        holding = true
        if (started && !muted) player.pause()
    }

    fun release() {
        holding = false
        if (started && !muted) player.resume()
    }

    fun mute(mute: Boolean) {
        if (mute == muted) return
        muted = mute
        if (mute) {
            job?.cancel()
            if (started) player.pause()
        } else {
            playing = null
            onSlide(index)
        }
    }

    /** Leaving the story: put back what the user was listening to. */
    fun restore() {
        job?.cancel()
        if (!started) return
        val track = before?.track
        if (track != null && !before.isPaused) {
            val at = before.positionAt(clock())
            player.play(track.uri)
            scope.launch {
                withTimeoutOrNull(4_000) { player.state.first { it?.track?.uri == track.uri } }
                player.seekTo(at)
            }
        } else {
            player.pause()
        }
    }

    private suspend fun track(cue: SongCue): Track? = synchronized(tracks) {
        tracks.getOrPut(cue.key) { scope.async { runCatching { resolve(cue) }.onFailure { Log.w(TAG, "No track for a Wrapped cue: ${it.message}") }.getOrNull() } }
    }.await()

    private suspend fun hook(track: Track): Long = synchronized(hooks) {
        hooks.getOrPut(track.uri) {
            scope.async {
                val found = (lyrics.lyrics(track.uri) as? LyricsResult.Found)?.lyrics
                WrappedSoundtrack.hookMs(found, track.durationMs)
            }
        }
    }.await()

    /** The Spotify track for a cue, by search: an exact name and artist match if there is one. */
    private suspend fun resolve(cue: SongCue): Track? {
        val artist = cue.artist?.lowercase(Locale.ROOT)
        fun Track.byArtist() = artist == null || artists.any { it.name.lowercase(Locale.ROOT) == artist }
        val query = buildString {
            if (cue.name.isNotBlank()) append("track:\"${cue.name}\" ")
            cue.artist?.let { append("artist:\"$it\"") }
        }.trim()
        val results = catalog.search(query, setOf(SearchType.Track), limit = 10).tracks?.items.orEmpty().filter { it.isPlayable && !it.isEpisode }
        if (cue.name.isBlank()) return results.firstOrNull { it.byArtist() }
        return results.firstOrNull { it.name.equals(cue.name, ignoreCase = true) && it.byArtist() }
            ?: results.firstOrNull { it.byArtist() }
            ?: results.firstOrNull()
    }

    private companion object {
        const val TAG = "WrappedSoundtrack"
    }
}
