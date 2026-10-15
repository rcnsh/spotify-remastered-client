package app.remastered.spotify.auth

import android.annotation.SuppressLint
import android.content.Context
import app.remastered.core.KeystoreSealer
import app.remastered.core.Sealer

/**
 * A signed-in session. [usesClientSecret] records that the code exchange needed the client
 * secret, so refreshes must authenticate the same way.
 */
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val scope: String,
    val usesClientSecret: Boolean = false,
) {
    override fun toString() = "Tokens(expiresAtEpochMs=$expiresAtEpochMs, scope=$scope, usesClientSecret=$usesClientSecret)"
}

/** A login in flight: kept across process death between launching the login and its result. */
data class PendingLogin(val verifier: String, val state: String) {
    override fun toString() = "PendingLogin(<redacted>)"
}

interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear()

    fun pendingLogin(): PendingLogin?
    fun savePendingLogin(pending: PendingLogin)
    fun clearPendingLogin()
}

class InMemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    private var pending: PendingLogin? = null
    @Synchronized override fun load() = tokens
    @Synchronized override fun save(tokens: Tokens) { this.tokens = tokens }
    @Synchronized override fun clear() { tokens = null }
    @Synchronized override fun pendingLogin() = pending
    @Synchronized override fun savePendingLogin(pending: PendingLogin) { this.pending = pending }
    @Synchronized override fun clearPendingLogin() { pending = null }
}

/**
 * SharedPreferences file `spotify_auth`; it must be excluded from backup (configured in res/xml).
 * The access and refresh tokens are sealed by [sealer] (a Keystore key by default), as the
 * warehouse token is. Tokens saved in the clear by older versions still load, and are sealed
 * then. If the key is lost they read as missing: signed out. Every Web API request asks for the
 * token, so what was loaded is kept in memory rather than opened from the Keystore each time.
 */
class PrefsTokenStore(context: Context, private val sealer: Sealer = KeystoreSealer(ALIAS)) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** What [load] last read or [save] wrote; null until then and after [clear]. */
    private var cached: Tokens? = null

    @Synchronized override fun load(): Tokens? = cached ?: read()?.also { cached = it }

    private fun read(): Tokens? {
        val access = prefs.getString(ACCESS, null)?.let { SealedValue.read(it, sealer) } ?: return null
        val refresh = prefs.getString(REFRESH, null)?.let { SealedValue.read(it, sealer) } ?: return null
        val tokens = Tokens(
            accessToken = access.value ?: return null,
            refreshToken = refresh.value ?: return null,
            expiresAtEpochMs = prefs.getLong(EXPIRES, 0),
            scope = prefs.getString(SCOPE, "").orEmpty(),
            usesClientSecret = prefs.getBoolean(USES_SECRET, false),
        )
        if (access.inClear || refresh.inClear) save(tokens)
        return tokens
    }

    @Synchronized override fun save(tokens: Tokens) {
        cached = tokens
        prefs.edit()
            .putString(ACCESS, SealedValue.write(tokens.accessToken, sealer))
            .putString(REFRESH, SealedValue.write(tokens.refreshToken, sealer))
            .putLong(EXPIRES, tokens.expiresAtEpochMs)
            .putString(SCOPE, tokens.scope)
            .putBoolean(USES_SECRET, tokens.usesClientSecret)
            .apply()
    }

    @Synchronized override fun clear() {
        cached = null
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(SCOPE).remove(USES_SECRET).apply()
    }

    override fun pendingLogin(): PendingLogin? {
        val verifier = prefs.getString(VERIFIER, null) ?: return null
        return PendingLogin(verifier, prefs.getString(STATE, "").orEmpty())
    }

    // commit(): the login activity may kill us right after launch; the verifier must be on disk.
    @SuppressLint("ApplySharedPref")
    override fun savePendingLogin(pending: PendingLogin) {
        prefs.edit().putString(VERIFIER, pending.verifier).putString(STATE, pending.state).commit()
    }

    override fun clearPendingLogin() {
        prefs.edit().remove(VERIFIER).remove(STATE).apply()
    }

    private companion object {
        const val FILE = "spotify_auth"
        const val ALIAS = "remastered_spotify_tokens"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val EXPIRES = "expires_at"
        const val SCOPE = "scope"
        const val USES_SECRET = "uses_client_secret"
        const val VERIFIER = "pkce_verifier"
        const val STATE = "pkce_state"
    }
}

/**
 * A secret as stored in preferences: [PREFIX] and [Sealer] output, or (from before secrets were
 * sealed) the plain value. Spotify's tokens never start with [PREFIX] (they're base64url).
 */
internal object SealedValue {
    const val PREFIX = "v1:"

    /** [value] is null when it was sealed but can't be opened; [inClear] when it was stored unsealed. */
    class Read(val value: String?, val inClear: Boolean)

    fun write(plain: String, sealer: Sealer): String = PREFIX + sealer.seal(plain)

    fun read(stored: String, sealer: Sealer): Read =
        if (stored.startsWith(PREFIX)) Read(sealer.open(stored.removePrefix(PREFIX)), inClear = false) else Read(stored, inClear = true)
}
