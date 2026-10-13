package app.remastered.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Merges what the user just did with what Spotify last reported, so controls respond
 * instantly although Spotify confirms them a moment later.
 *
 * Each local edit (pause, seek, shuffle, repeat, like) is an override on top of the remote
 * state. An override is dropped once Spotify reports the same value, or after [holdMs]
 * (then Spotify's value wins: the command failed or was refused). A track change drops the
 * overrides that belonged to the old track.
 */
class OptimisticPlayback(
    private val clock: () -> Long,
    private val holdMs: Long = 2_500,
    private val seekToleranceMs: Long = 1_500,
) {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private var remote: PlaybackState? = null
    private var paused: Timed<Boolean>? = null
    private var seek: Timed<Long>? = null
    private var shuffle: Timed<Boolean>? = null
    private var repeat: Timed<RepeatMode>? = null
    private var liked: Timed<Pair<String, Boolean>>? = null

    private class Timed<T>(val value: T, val at: Long)

    @Synchronized
    fun onRemote(update: PlaybackState?) {
        val now = clock()
        if (update?.track?.uri != remote?.track?.uri) {
            seek = null
            liked = liked?.takeIf { it.value.first == update?.track?.uri }
        }
        remote = update
        if (update != null) {
            paused = paused?.takeUnless { it.value == update.isPaused }
            shuffle = shuffle?.takeUnless { it.value == update.shuffle }
            repeat = repeat?.takeUnless { it.value == update.repeat }
            liked = liked?.takeUnless { it.value.second == update.track?.liked }
            seek = seek?.takeUnless { s ->
                val expected = s.value + if (update.isPaused) 0 else (now - s.at)
                abs(update.positionAt(now) - expected) <= seekToleranceMs
            }
        }
        publish()
    }

    @Synchronized fun setPaused(value: Boolean) = edit { anchorPosition(); paused = Timed(value, clock()) }
    @Synchronized fun seekTo(positionMs: Long) = edit { seek = Timed(positionMs.coerceAtLeast(0), clock()) }
    @Synchronized fun setShuffle(value: Boolean) = edit { shuffle = Timed(value, clock()) }
    @Synchronized fun setRepeat(value: RepeatMode) = edit { repeat = Timed(value, clock()) }
    @Synchronized fun setLiked(uri: String, value: Boolean) = edit { liked = Timed(uri to value, clock()) }

    /** Re-evaluates expiry; call on a timer so a refused command settles back. */
    @Synchronized fun tick() = publish()

    /** Pausing freezes the shown position where it is, so the bar doesn't jump back. */
    private fun anchorPosition() {
        if (seek == null) {
            val shown = _state.value ?: return
            seek = Timed(shown.positionAt(clock()), clock())
        }
    }

    private inline fun edit(block: () -> Unit) {
        block()
        publish()
    }

    private fun publish() {
        val now = clock()
        fun <T> Timed<T>?.live() = this?.takeIf { now - it.at < holdMs }
        paused = paused.live(); seek = seek.live(); shuffle = shuffle.live()
        repeat = repeat.live(); liked = liked.live()

        val base = remote ?: run { _state.value = null; return }
        var s = base
        paused?.let { s = s.copy(isPaused = it.value) }
        shuffle?.let { s = s.copy(shuffle = it.value) }
        repeat?.let { s = s.copy(repeat = it.value) }
        liked?.value?.let { (uri, value) -> if (s.track?.uri == uri) s = s.copy(track = s.track.copy(liked = value)) }
        seek?.let { s = s.copy(positionMs = it.value, positionAtMs = it.at) }
        _state.value = s
    }
}
