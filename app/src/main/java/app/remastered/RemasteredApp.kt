package app.remastered

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.remastered.ui.components.RemoteImageFetcher
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

/** Holds the current [AppContainer]; [restart] replaces it. */
class RemasteredApp : Application(), SingletonImageLoader.Factory {
    var container by mutableStateOf<AppContainer?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Coil's loader: https artwork through its network fetcher, plus `appremote:` artwork
     * (Now Playing) fetched from the Spotify app over App Remote by whichever container is live.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(RemoteImageFetcher.Factory { raw -> container?.spotify?.player?.image(raw, large = true) })
            add(RemoteImageFetcher.Key)
        }
        .build()

    /** Tears everything down and starts fresh: a new App Remote connection, empty in-memory caches. */
    fun restart() {
        container?.close()
        container = AppContainer(this)
    }

    /** Signs out and deletes every setting, recent search and cached lyric, then [restart]s. Clear images separately. */
    fun reset() {
        container?.spotify?.signOut()
        container?.lyricsCache?.clear()
        listOf(AppContainer.PREFS_SETTINGS, AppContainer.PREFS_SEARCH).forEach {
            getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        restart()
    }
}
