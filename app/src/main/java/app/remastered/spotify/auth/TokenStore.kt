package app.remastered.spotify.auth

import android.content.Context

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

/** SharedPreferences file `spotify_auth`; it must be excluded from backup (configured in res/xml). */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Tokens? {
        val access = prefs.getString(ACCESS, null) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = prefs.getString(REFRESH, null) ?: return null,
            expiresAtEpochMs = prefs.getLong(EXPIRES, 0),
            scope = prefs.getString(SCOPE, "").orEmpty(),
            usesClientSecret = prefs.getBoolean(USES_SECRET, false),
        )
    }

    override fun save(tokens: Tokens) {
        prefs.edit()
            .putString(ACCESS, tokens.accessToken)
            .putString(REFRESH, tokens.refreshToken)
            .putLong(EXPIRES, tokens.expiresAtEpochMs)
            .putString(SCOPE, tokens.scope)
            .putBoolean(USES_SECRET, tokens.usesClientSecret)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(SCOPE).remove(USES_SECRET).apply()
    }

    override fun pendingLogin(): PendingLogin? {
        val verifier = prefs.getString(VERIFIER, null) ?: return null
        return PendingLogin(verifier, prefs.getString(STATE, "").orEmpty())
    }

    // commit(): the login activity may kill us right after launch; the verifier must be on disk.
    override fun savePendingLogin(pending: PendingLogin) {
        prefs.edit().putString(VERIFIER, pending.verifier).putString(STATE, pending.state).commit()
    }

    override fun clearPendingLogin() {
        prefs.edit().remove(VERIFIER).remove(STATE).apply()
    }

    private companion object {
        const val FILE = "spotify_auth"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val EXPIRES = "expires_at"
        const val SCOPE = "scope"
        const val USES_SECRET = "uses_client_secret"
        const val VERIFIER = "pkce_verifier"
        const val STATE = "pkce_state"
    }
}
