package app.remastered.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sleep timer: pauses playback after a number of minutes, or when the song playing ends.
 * Only the timing lives here, on [clock] (`SystemClock.elapsedRealtime()` on Android, the
 * player's clock). `media/NowPlayingService` drives it with [step], so it keeps running with no
 * window open.
 */
class SleepTimer(private val clock: () -> Long) {
    sealed interface Mode {
        /** Pause at [atMs] on the clock, [minutes] after it was set. */
        data class After(val minutes: Int, val atMs: Long) : Mode
        /** Pause when the song playing ends. Follows skips: it's always the song playing now. */
        data object EndOfTrack : Mode
    }

    /** What the driver should do next. */
    sealed interface Step {
        /** Nothing until the mode or the playback state changes. */
        data object Idle : Step
        /** Pause now. The timer has turned itself off. */
        data object Pause : Step
        /** Call [step] again after [ms] (or sooner, on a change). */
        data class Wait(val ms: Long) : Step
    }

    private val _mode = MutableStateFlow<Mode?>(null)
    /** Null while off. */
    val mode: StateFlow<Mode?> = _mode.asStateFlow()

    /** The song [Mode.EndOfTrack] last saw playing, and when it ends on the clock (null while paused). */
    private var ending: Pair<String, Long?>? = null

    fun start(minutes: Int) {
        ending = null
        _mode.value = Mode.After(minutes, clock() + minutes * 60_000L)
    }

    fun startEndOfTrack() {
        ending = null
        _mode.value = Mode.EndOfTrack
    }

    fun cancel() {
        ending = null
        _mode.value = null
    }

    /** Time left for [Mode.After], at least 0; null otherwise. */
    fun remainingMs(now: Long = clock()): Long? = (_mode.value as? Mode.After)?.let { (it.atMs - now).coerceAtLeast(0) }

    /** What to do now, given what's playing. Returning [Step.Pause] turns the timer off. */
    fun step(state: PlaybackState?): Step {
        val now = clock()
        return when (val m = _mode.value) {
            null -> Step.Idle
            is Mode.After -> when {
                now < m.atMs -> Step.Wait(m.atMs - now)
                // Long past: nothing was running to pause then (the process was gone, say). Don't
                // pause whatever has started since.
                now - m.atMs > STALE_MS -> { cancel(); Step.Idle }
                else -> { cancel(); Step.Pause }
            }
            Mode.EndOfTrack -> endOfTrack(state, now)
        }
    }

    private fun endOfTrack(state: PlaybackState?, now: Long): Step {
        val track = state?.track ?: return Step.Idle
        val last = ending
        // The next song has started on its own (we heard of the end late): pause it at once.
        // Far from the end it was a skip, and the timer moves on to the new song.
        if (last != null && last.first != track.uri && last.second != null && now >= last.second!! - NATURAL_END_MS) {
            cancel()
            return Step.Pause
        }
        if (state.isPaused) {
            ending = track.uri to null
            return Step.Idle
        }
        val speed = state.speed.takeIf { it > 0f } ?: 1f
        val left = ((track.durationMs - state.positionAt(now)) / speed).toLong()
        ending = track.uri to now + left
        if (left <= EARLY_MS) {
            cancel()
            return Step.Pause
        }
        return Step.Wait(left - EARLY_MS)
    }

    companion object {
        /** The choices offered, in minutes. */
        val MINUTES = listOf(5, 10, 15, 30, 45, 60)
        /**
         * Pause this long before the song's end: App Remote's position is an estimate, and
         * cutting a second of the outro beats a blip of the next song.
         */
        const val EARLY_MS = 1_000L
        /** A new song this close to the old one's end means it ended, rather than being skipped. */
        const val NATURAL_END_MS = 3_000L
        /** A timed pause missed by more than this is dropped instead of done late. */
        const val STALE_MS = 60_000L
    }
}
