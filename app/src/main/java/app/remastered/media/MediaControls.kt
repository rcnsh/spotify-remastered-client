package app.remastered.media

import android.media.session.PlaybackState as SessionState
import app.remastered.core.PlaybackState

/**
 * What Remastered's media notification and session show for a [PlaybackState]: plain values,
 * so the mapping is testable without Android. Null when there's nothing to show (no track).
 */
data class MediaControls(
    val playing: Boolean,
    /** [SessionState] `ACTION_*` flags the system media controls may offer. */
    val actions: Long,
    val positionMs: Long,
    /** When [positionMs] was true, on `SystemClock.elapsedRealtime()` (the player's clock). */
    val positionAtMs: Long,
    val speed: Float,
    /** Songs can be liked; podcast episodes can't. */
    val canLike: Boolean,
    val liked: Boolean,
) {
    companion object {
        fun of(state: PlaybackState?): MediaControls? {
            val track = state?.track ?: return null
            var actions = SessionState.ACTION_PLAY_PAUSE or
                (if (state.isPaused) SessionState.ACTION_PLAY else SessionState.ACTION_PAUSE)
            if (state.canSeek) actions = actions or SessionState.ACTION_SEEK_TO
            if (state.canSkipNext) actions = actions or SessionState.ACTION_SKIP_TO_NEXT
            if (state.canSkipPrevious) actions = actions or SessionState.ACTION_SKIP_TO_PREVIOUS
            return MediaControls(
                playing = !state.isPaused,
                actions = actions,
                positionMs = state.positionAt(state.positionAtMs),
                positionAtMs = state.positionAtMs,
                speed = if (state.isPaused) 0f else state.speed,
                canLike = !track.isEpisode,
                liked = track.liked,
            )
        }
    }
}
