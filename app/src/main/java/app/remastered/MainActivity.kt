package app.remastered

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.remastered.ui.RemasteredRoot
import app.remastered.ui.theme.RemasteredTheme

class MainActivity : ComponentActivity() {
    private companion object { const val EXTRA_LYRICS_PREVIEW = "lyrics_preview" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RemasteredApp
        // Debug builds only: `adb shell am start -n app.remastered/.MainActivity --es lyrics_preview spotify:track:…`
        // opens that track's lyrics on a local clock, for checking the lyrics view without Spotify.
        val lyricsPreview = mutableStateOf(intent.getStringExtra(EXTRA_LYRICS_PREVIEW)?.takeIf { BuildConfig.DEBUG && it.startsWith("spotify:track:") })
        setContent {
            val container = app.container
            RemasteredTheme {
                container?.let { RemasteredRoot(it, lyricsPreview) }
            }
        }
    }
}
