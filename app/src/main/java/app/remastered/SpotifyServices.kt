package app.remastered

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.remastered.core.AppSettings
import app.remastered.core.Catalog
import app.remastered.spotify.auth.AuthException
import app.remastered.spotify.auth.SpotifyAuth
import app.remastered.spotify.remote.AppRemotePlayer
import app.remastered.spotify.web.PrefsGateStore
import app.remastered.spotify.web.RequestLog
import app.remastered.spotify.web.WebApiGate
import app.remastered.spotify.web.WebCatalog
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The real Spotify: App Remote for playback, the Web API (signed in with PKCE) for everything else. */
class SpotifyServices(context: Context, scope: CoroutineScope, clock: () -> Long, settings: AppSettings) {
    /** Every Web API and token request, for Settings → Developer. */
    val requests = RequestLog(logcat = { settings.logRequests.on })

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(requests)
        .build()

    val auth = SpotifyAuth.create(
        context.applicationContext, http, BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI,
        BuildConfig.SPOTIFY_CLIENT_SECRET.ifEmpty { null },
    )
    val player = AppRemotePlayer(context.applicationContext, BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI, scope, clock)
    /**
     * Every Web API request's way out: a shared budget, and a pause for all of them when Spotify
     * sends `Retry-After`, kept across restarts. App Remote doesn't go through it.
     */
    val gate = WebApiGate(PrefsGateStore(context))
    val catalog: Catalog = WebCatalog(http, auth.tokenProvider, gate = gate)

    /** Observable for the UI; refreshed after sign-in, sign-out and auth failures. */
    var signedIn by mutableStateOf(auth.isSignedIn)
        private set

    fun loginIntent(activity: Activity): Intent = auth.loginIntent(activity)

    /** Finishes sign-in from the auth activity's result. Returns an error message, or null on success. */
    suspend fun finishLogin(resultCode: Int, data: Intent?): String? {
        val result = auth.handleResult(resultCode, data)
        signedIn = auth.isSignedIn
        val e = result.exceptionOrNull() ?: return null
        // Error codes only: never log the response itself, it may carry a code or token.
        Log.w("SpotifyServices", "Sign-in failed: ${e.javaClass.simpleName} ${(e as? AuthException)?.let { "status=${it.status} error=${it.error}" }.orEmpty()}")
        val detail = (e as? AuthException)?.takeIf { it.status != null }?.let { " (HTTP ${it.status}${it.error?.let { err -> ", $err" }.orEmpty()})" }.orEmpty()
        return (e.message ?: "Sign-in failed.") + detail
    }

    fun signOut() {
        auth.signOut()
        signedIn = false
    }

    /**
     * Whether the session was granted [scope]. Scopes added since the user signed in (like
     * `ugc-image-upload` for playlist covers) need a fresh sign-in, so features check this first.
     */
    fun hasScope(scope: String): Boolean = auth.session()?.scope?.split(' ')?.contains(scope) == true

    /** Re-reads the token store, e.g. after a refresh was rejected and the tokens were cleared. */
    fun recheck() { signedIn = auth.isSignedIn }

    companion object {
        /** False when client/local.properties has no `spotify.clientId`: nothing can work. */
        val isConfigured get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()
    }
}
