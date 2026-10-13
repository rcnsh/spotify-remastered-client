package app.remastered

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.remastered.ui.RemasteredRoot
import app.remastered.ui.wrapped.WrappedPreview
import app.remastered.ui.theme.RemasteredTheme

class MainActivity : ComponentActivity() {
    private companion object {
        const val EXTRA_LYRICS_PREVIEW = "lyrics_preview"
        const val EXTRA_WRAPPED_PREVIEW = "wrapped_preview"
        const val EXTRA_WRAPPED_FRAME = "wrapped_frame"
        const val EXTRA_WRAPPED_DUMP = "wrapped_dump"
        const val EXTRA_WRAPPED_EXPORT = "wrapped_export"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RemasteredApp
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
                container?.let { RemasteredRoot(it, lyricsPreview, wrappedPreview) }
            }
        }
    }
}
