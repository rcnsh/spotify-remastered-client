package app.remastered.core.lyrics

import app.remastered.core.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

sealed interface LyricsState {
    /** Lyrics are switched off in Settings. */
    data object Off : LyricsState
    /** Nothing is playing. */
    data object Idle : LyricsState
    data class Loading(val trackUri: String) : LyricsState
    data class Ready(val trackUri: String, val result: LyricsResult) : LyricsState
}

/**
 * Lyrics for whatever [player] is playing. Fetches only while something observes [state] (Now
 * Playing or the lyrics screen is open) and once per song.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentLyrics(player: Player, private val source: LyricsSource, enabled: StateFlow<Boolean>, scope: CoroutineScope) {
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<LyricsState> = combine(
        player.state.map { it?.track?.uri }.distinctUntilChanged(), enabled, attempt,
    ) { uri, on, _ -> uri to on }
        .transformLatest { (uri, on) ->
            when {
                !on -> emit(LyricsState.Off)
                uri == null -> emit(LyricsState.Idle)
                else -> {
                    emit(LyricsState.Loading(uri))
                    emit(LyricsState.Ready(uri, source.lyrics(uri)))
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LyricsState.Idle)

    /** Fetches again, e.g. after a network failure. */
    fun retry() { attempt.value++ }
}
