package app.remastered.spotify.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.remastered.spotify.web.TokenProvider
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

/**
 * Sign-in via Spotify's auth library: authorization-code flow with PKCE, tokens in [store].
 *
 * Usage: launch [loginIntent] with an activity-result launcher, pass the result to [handleResult],
 * then build `WebCatalog(http, auth.tokenProvider)`.
 */
class SpotifyAuth(
    private val clientId: String,
    private val redirectUri: String,
    private val store: TokenStore,
    private val tokenClient: TokenClient,
    private val scopes: List<String> = SCOPES,
) {
    val tokenProvider: TokenProvider = StoredTokenProvider(store, tokenClient)

    val isSignedIn get() = store.load() != null

    fun loginIntent(activity: Activity): Intent {
        val random = SecureRandom()
        val pending = PendingLogin(Pkce.verifier(random), Pkce.verifier(random).take(32))
        // Persisted before launch: the process may die while the login UI is in front.
        store.savePendingLogin(pending)
        val request = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.CODE, redirectUri)
            .setScopes(scopes.toTypedArray())
            .setState(pending.state)
            .setCustomParam("code_challenge", Pkce.challenge(pending.verifier))
            .setCustomParam("code_challenge_method", "S256")
            .build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    suspend fun handleResult(resultCode: Int, data: Intent?): Result<Tokens> {
        val pending = store.pendingLogin()
        return try {
            val response = AuthorizationClient.getResponse(resultCode, data)
            when (response.type) {
                AuthorizationResponse.Type.CODE -> {
                    pending ?: throw AuthException("No login in progress")
                    // Spotify echoes state on both the browser and app paths; reject a mismatch.
                    if (response.state != null && response.state != pending.state) {
                        throw AuthException("Login state mismatch")
                    }
                    val tokens = tokenClient.exchange(response.code, pending.verifier)
                    store.save(tokens)
                    Result.success(tokens)
                }
                AuthorizationResponse.Type.ERROR -> throw AuthException("Spotify login failed (${response.error ?: "no error code"})", null, response.error)
                AuthorizationResponse.Type.EMPTY -> throw AuthException("Login cancelled", null, "cancelled")
                else -> throw AuthException("Unexpected login response: ${response.type}")
            }
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
    }
}
