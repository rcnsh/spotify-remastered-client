package app.remastered.warehouse

import android.annotation.SuppressLint
import android.content.Context
import app.remastered.spotify.auth.Pkce
import app.remastered.spotify.web.await
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * "Sign in with Spotify" against the user's music-warehouse (its README, "Signing in with
 * Spotify"): the warehouse checks the Spotify account owns it and mints a read token for this
 * phone. Not the app's own Spotify Web API login (spotify/auth), which goes to Spotify directly.
 *
 * PKCE with a custom-scheme redirect (RFC 8252): [start] makes a verifier, keeps it in [store]
 * (the process may die while the browser is in front) and returns the `/auth/login` URL to open;
 * the warehouse sends the browser back to [RETURN_TO] with `?code=` or `?error=`, and [complete]
 * trades the code and verifier at `/auth/token` for the token. A code lives 60 seconds and works
 * once, so it's exchanged straight away and never retried.
 */
class WarehouseSignIn(
    private val http: OkHttpClient,
    private val store: PendingSignInStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The page to open in a Custom Tab, for the warehouse at [baseUrl] (normalized, see [WarehouseConfig.normalizeUrl]). */
    fun start(baseUrl: String, deviceName: String): HttpUrl {
        val verifier = Pkce.verifier(random)
        store.save(PendingSignIn(baseUrl, verifier, clock()))
        return loginUrl(baseUrl, deviceName, Pkce.challenge(verifier))
    }

    /**
     * Finishes the sign-in [redirect] (the `app.remastered://warehouse-auth?…` uri) belongs to: the
     * config to save, or a [SignInException] whose message can be shown as is. The pending
     * sign-in is used up either way.
     */
    suspend fun complete(redirect: String): Result<WarehouseConfig> {
        val pending = store.load()
        store.clear()
        return try {
            val code = when (val r = parseRedirect(redirect)) {
                is Redirect.Code -> r.code
                is Redirect.Error -> throw SignInException(errorMessage(r.error))
                Redirect.NotOurs -> throw SignInException("That link isn't a warehouse sign-in.")
            }
            pending?.takeIf { clock() - it.startedMs in 0..PENDING_TTL_MS }
                ?: throw SignInException("No sign-in in progress, or it took too long. Try again.")
            Result.success(WarehouseConfig(pending.baseUrl, exchange(pending, code)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInException) {
            Result.failure(e)
        }
    }

    /** True while a sign-in has been started and not completed, for showing "waiting for the browser". */
    val inProgress get() = store.load()?.let { clock() - it.startedMs in 0..PENDING_TTL_MS } == true

    fun cancel() = store.clear()

    /** POST /auth/token with no Origin (a native app); returns the minted token. */
    private suspend fun exchange(pending: PendingSignIn, code: String): String {
        val body = WarehouseApi.json.encodeToString(TokenRequestDto.serializer(), TokenRequestDto(code, pending.verifier))
        val request = Request.Builder().url(pending.baseUrl.toHttpUrl().newBuilder().addPathSegments("auth/token").build())
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val (status, text) = try {
            withContext(io) { http.newCall(request).await().use { it.code to it.body.string() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SignInException("Can't reach the warehouse. Check your connection and try again.", e)
        }
        if (status == 400 || status == 401) throw SignInException("The sign-in expired or was already used. Try again.")
        if (status == 404) throw SignInException("This warehouse doesn't support signing in with Spotify yet. Enter a token instead.")
        if (status !in 200..299) throw SignInException("The warehouse answered $status. Try again later.")
        val minted = try {
            WarehouseApi.json.decodeFromString(TokenResponseDto.serializer(), text)
        } catch (e: Exception) {
            throw SignInException("The warehouse sent something this version can't read.", e)
        }
        // The phone holds read tokens only; sign-in never mints anything else, but check.
        if (minted.access != null && minted.access != "read") throw SignInException("The warehouse minted a token that isn't read-only; not saving it.")
        return minted.token?.takeIf { it.isNotBlank() } ?: throw SignInException("The warehouse sent no token.")
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect {
            override fun toString() = "Code(<redacted>)"
        }
        data class Error(val error: String) : Redirect
        /** Not a warehouse sign-in redirect at all. */
        data object NotOurs : Redirect
    }

    companion object {
        const val SCHEME = "app.remastered"
        /** Not `callback`: that host is the Web API login's redirect uri (spotify/auth). */
        const val HOST = "warehouse-auth"
        const val RETURN_TO = "$SCHEME://$HOST"
        /** The warehouse forgets a login after 10 minutes. */
        const val PENDING_TTL_MS = 10 * 60 * 1000L
        private const val MAX_NAME = 64
        private val JSON = "application/json".toMediaType()

        /** "Android (Pixel 10 Pro)", the name the token gets in the warehouse's list. */
        fun deviceName(model: String): String = "Android (${model.trim().ifEmpty { "phone" }})".take(MAX_NAME)

        fun loginUrl(baseUrl: String, deviceName: String, challenge: String): HttpUrl =
            baseUrl.toHttpUrl().newBuilder().addPathSegments("auth/login")
                .addQueryParameter("name", deviceName.take(MAX_NAME))
                .addQueryParameter("return_to", RETURN_TO)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build()

        fun isRedirect(uri: String?): Boolean = uri != null && parseRedirect(uri) != Redirect.NotOurs

        fun parseRedirect(uri: String): Redirect {
            val parsed = try { URI(uri) } catch (_: Exception) { return Redirect.NotOurs }
            if (!parsed.scheme.equals(SCHEME, ignoreCase = true) || !parsed.host.equals(HOST, ignoreCase = true)) return Redirect.NotOurs
            val params = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                decode(k) to decode(v)
            }
            params["code"]?.takeIf { it.isNotBlank() }?.let { return Redirect.Code(it) }
            return Redirect.Error(params["error"]?.takeIf { it.isNotBlank() } ?: "missing_code")
        }

        fun errorMessage(error: String): String = when (error) {
            "access_denied" -> "Sign-in cancelled."
            "not_owner" -> "That Spotify account doesn't own this warehouse. Sign in with the account that set it up."
            "unavailable" -> "The warehouse can't sign you in right now. Try again later."
            else -> "Sign-in failed ($error). Try again."
        }

        private fun decode(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}

class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A sign-in in flight: which warehouse it's for and the PKCE verifier. */
data class PendingSignIn(val baseUrl: String, val verifier: String, val startedMs: Long) {
    override fun toString() = "PendingSignIn(baseUrl=$baseUrl, <redacted>)"
}

interface PendingSignInStore {
    fun load(): PendingSignIn?
    fun save(pending: PendingSignIn)
    fun clear()
}

class InMemoryPendingSignInStore(private var pending: PendingSignIn? = null) : PendingSignInStore {
    @Synchronized override fun load() = pending
    @Synchronized override fun save(pending: PendingSignIn) { this.pending = pending }
    @Synchronized override fun clear() { pending = null }
}

/** In the `warehouse` SharedPreferences file, next to the config (excluded from backup in res/xml). */
class PrefsPendingSignInStore(context: Context) : PendingSignInStore {
    private val prefs = context.applicationContext.getSharedPreferences("warehouse", Context.MODE_PRIVATE)

    override fun load(): PendingSignIn? {
        val url = prefs.getString(URL, null) ?: return null
        val verifier = prefs.getString(VERIFIER, null) ?: return null
        return PendingSignIn(url, verifier, prefs.getLong(STARTED, 0))
    }

    // commit(): the Custom Tab may get us killed right after launch; the verifier must be on disk.
    @SuppressLint("ApplySharedPref")
    override fun save(pending: PendingSignIn) {
        prefs.edit().putString(URL, pending.baseUrl).putString(VERIFIER, pending.verifier).putLong(STARTED, pending.startedMs).commit()
    }

    override fun clear() {
        prefs.edit().remove(URL).remove(VERIFIER).remove(STARTED).apply()
    }

    private companion object {
        const val URL = "sign_in_base_url"
        const val VERIFIER = "sign_in_verifier"
        const val STARTED = "sign_in_started"
    }
}

@Serializable
internal data class TokenRequestDto(val code: String, @SerialName("code_verifier") val codeVerifier: String)

@Serializable
internal data class TokenResponseDto(
    val id: String? = null,
    val token: String? = null,
    val name: String? = null,
    val access: String? = null,
    @SerialName("expires_ms") val expiresMs: Long? = null,
)
