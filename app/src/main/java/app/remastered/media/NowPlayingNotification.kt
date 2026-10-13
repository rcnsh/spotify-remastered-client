package app.remastered.media

import android.content.Context
import android.util.Log
import app.remastered.core.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts [NowPlayingService] when something starts playing (and the setting is on), and stops
 * it when the setting is turned off. The service stops itself once it has nothing to show.
 */
class NowPlayingNotification(private val context: Context, player: Player, enabled: StateFlow<Boolean>, scope: CoroutineScope) {
    init {
        scope.launch {
            combine(enabled, player.state) { on, s -> if (!on) Want.Off else if (s?.track != null && !s.isPaused) Want.Playing else Want.Either }
                .distinctUntilChanged()
                .collect { want ->
                    when (want) {
                        Want.Off -> stop()
                        Want.Playing -> start()
                        Want.Either -> Unit
                    }
                }
        }
    }

    private enum class Want { Off, Playing, Either }

    private fun start() {
        try {
            context.startForegroundService(NowPlayingService.intent(context))
        } catch (e: IllegalStateException) {
            // Playback started while Remastered was in the background (another device, a
            // headset): Android doesn't allow starting it then. It starts next time it can.
            Log.w(TAG, "Can't start the media notification: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        context.stopService(NowPlayingService.intent(context))
    }

    private companion object {
        const val TAG = "NowPlayingNotification"
    }
}
