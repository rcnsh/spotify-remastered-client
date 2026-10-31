package app.remastered.spotify.auth

import android.content.Context
import app.remastered.spotify.web.TokenProvider
import java.net.URI
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Sign-in to the Web API: authorization code with PKCE in the browser, tokens in [store].
 *
 * [loginUrl] keeps a verifier and state in [store] (the process may die while the browser is in
 * front) and returns Spotify's authorize page for a Custom Tab; Spotify sends the browser back to
 * [redirectUri], and [handleRedirect] trades the code. Not Spotify's auth library: it hands the
 * login to the Spotify app when it can, which drops the code challenge, and the exchange then
 * needs the client secret, which a published APK can't carry.
 */
class SpotifyAuth(
    private val clientId: String,
    private val redirectUri: String,
    private val store: TokenStore,
    private val tokenClient: TokenClient,
    private val scopes: List<String> = SCOPES,
    private val random: SecureRandom = SecureRandom(),
) {
    val tokenProvider: TokenProvider = StoredTokenProvider(store, tokenClient)

    val isSignedIn get() = store.load() != null

    fun loginUrl(): HttpUrl {
        val pending = PendingLogin(Pkce.verifier(random), Pkce.verifier(random).take(32))
        // Persisted before launch: the process may die while the browser is in front.
        store.savePendingLogin(pending)
        return AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", pending.state)
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", Pkce.challenge(pending.verifier))
            .build()
    }

    /** True for Spotify sending the browser back to [redirectUri] (any query). */
    fun isRedirect(uri: String?): Boolean {
        val ours = parse(redirectUri) ?: return false
        val parsed = uri?.let(::parse) ?: return false
        return parsed.scheme.equals(ours.scheme, ignoreCase = true) && parsed.host.equals(ours.host, ignoreCase = true)
    }

    /** Finishes the login [redirect] belongs to. The pending login is used up either way. */
    suspend fun handleRedirect(redirect: String): Result<Tokens> {
        val pending = store.pendingLogin()
        return try {
            val params = query(redirect)
            params["error"]?.let { error ->
                if (error == "access_denied") throw AuthException("Login cancelled", null, "cancelled")
                throw AuthException("Spotify login failed ($error)", null, error)
            }
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: throw AuthException("Spotify sent no code")
            pending ?: throw AuthException("No login in progress")
            if (params["state"] != pending.state) throw AuthException("Login state mismatch")
            val tokens = tokenClient.exchange(code, pending.verifier)
            store.save(tokens)
            Result.success(tokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            store.clearPendingLogin()
        }
    }

    fun signOut() {
        store.clear()
        store.clearPendingLogin()
    }

    /** The stored session, for Settings. Its toString() redacts the tokens; never show or log them. */
    fun session(): Tokens? = store.load()

    /** Developer tool: marks the access token expired so the next request goes through a refresh. */
    fun expireAccessToken() {
        store.load()?.let { store.save(it.copy(expiresAtEpochMs = 0)) }
    }

    companion object {
        const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"

        val SCOPES = listOf(
            "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing",
            "user-read-recently-played", "user-top-read", "user-library-read", "user-library-modify",
            "playlist-read-private", "playlist-read-collaborative", "playlist-modify-private",
            "playlist-modify-public", "user-follow-read", "user-follow-modify", "user-read-private",
            "app-remote-control", "streaming", "ugc-image-upload",
        )

        /** [clientSecret] is optional; blank means PKCE only. */
        fun create(context: Context, http: OkHttpClient, clientId: String, redirectUri: String, clientSecret: String? = null) =
            SpotifyAuth(
                clientId = clientId,
                redirectUri = redirectUri,
                store = PrefsTokenStore(context),
                tokenClient = TokenClient(http, clientId, redirectUri, clientSecret),
            )

        private fun parse(uri: String): URI? = try { URI(uri) } catch (_: Exception) { null }

        private fun query(uri: String): Map<String, String> =
            parse(uri)?.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                decode(k) to decode(v)
            }

        private fun decode(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}
