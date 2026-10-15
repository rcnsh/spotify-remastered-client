package app.remastered

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.remastered.ui.RemasteredRoot
import app.remastered.ui.wrapped.WrappedPreview
import app.remastered.ui.theme.RemasteredTheme
import app.remastered.warehouse.WarehouseSignIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** From the media notification (media/NowPlayingService): open Now Playing. */
        const val ACTION_OPEN_NOW_PLAYING = "app.remastered.OPEN_NOW_PLAYING"
        private const val EXTRA_LYRICS_PREVIEW = "lyrics_preview"
        private const val EXTRA_WRAPPED_PREVIEW = "wrapped_preview"
        private const val EXTRA_WRAPPED_FRAME = "wrapped_frame"
        private const val EXTRA_WRAPPED_DUMP = "wrapped_dump"
        private const val EXTRA_WRAPPED_EXPORT = "wrapped_export"
    }

    private val openNowPlaying = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RemasteredApp
        openNowPlaying.value = intent?.action == ACTION_OPEN_NOW_PLAYING
        // Not again when recreated (rotation): the intent is the same, and its code is spent.
        if (savedInstanceState == null) handleWarehouseSignIn(intent)
        // Debug builds only: `adb shell am start -n app.remastered/.MainActivity --es lyrics_preview spotify:track:…`
        // opens that track's lyrics on a local clock, for checking the lyrics view without Spotify.
        val lyricsPreview = mutableStateOf(intent.getStringExtra(EXTRA_LYRICS_PREVIEW)?.takeIf { BuildConfig.DEBUG && it.startsWith("spotify:track:") })
        // Debug builds only: `--es wrapped_preview 2025-01-01:2025-12-31 [--ei wrapped_frame 423]`
        // opens that range's Wrapped story from the warehouse (debug READ token), signed in or not;
        // with a frame, it opens paused there; `--es wrapped_dump 110,244` also renders those frames
        // to PNGs in the app's external files (wrapped/), for comparing with the web stills.
        // `--es wrapped_export silent|<song uri>` starts a video export at once.
        val wrappedPreview = mutableStateOf(
            (if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_WRAPPED_PREVIEW) else null)?.let(WrappedPreview::parse)
                ?.copy(
                    frame = intent.getIntExtra(EXTRA_WRAPPED_FRAME, -1).takeIf { it >= 0 },
                    dump = intent.getStringExtra(EXTRA_WRAPPED_DUMP)?.split(',')?.mapNotNull { it.trim().toIntOrNull() },
                    export = intent.getStringExtra(EXTRA_WRAPPED_EXPORT),
                ),
        )
        setContent {
            val container = app.container
            RemasteredTheme {
                container?.let { RemasteredRoot(it, lyricsPreview, wrappedPreview, openNowPlaying) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_NOW_PLAYING) openNowPlaying.value = true
        handleWarehouseSignIn(intent)
    }

    /**
     * The warehouse's "Sign in with Spotify" coming back (app.remastered://warehouse-auth?code=…):
     * trade the code for a read token. Runs in the container's scope so it outlives this activity,
     * and says how it went in a toast and in Settings → Listening history.
     */
    private fun handleWarehouseSignIn(intent: Intent?) {
        val uri = intent?.data?.toString()?.takeIf { intent.action == Intent.ACTION_VIEW && WarehouseSignIn.isRedirect(it) } ?: return
        val container = (application as RemasteredApp).container ?: return
        val ctx = applicationContext
        container.scope.launch {
            val result = container.warehouse.finishSignIn(uri)
            Toast.makeText(ctx, result.fold({ "Listening history: signed in" }, { it.message ?: "Sign-in failed" }), Toast.LENGTH_LONG).show()
        }
    }
}
