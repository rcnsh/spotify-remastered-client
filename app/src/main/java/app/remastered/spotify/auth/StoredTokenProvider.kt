package app.remastered.spotify.auth

import app.remastered.spotify.web.TokenProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TokenProvider] over a [TokenStore], refreshing through [TokenClient] when the token expires
 * within [REFRESH_MARGIN_MS]. Refreshes are single-flight: concurrent callers share one.
 * A rejected refresh token (`invalid_grant`) signs the user out.
 */
class StoredTokenProvider(
    private val store: TokenStore,
    private val client: TokenClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenProvider {
    private val mutex = Mutex()

    override suspend fun accessToken(): String = mutex.withLock {
        val tokens = store.load() ?: throw notSignedIn()
        if (tokens.expiresAtEpochMs - clock() > REFRESH_MARGIN_MS) tokens.accessToken else refreshLocked(tokens)
    }

    override suspend fun refresh(): String {
        val seen = store.load()?.accessToken
        return mutex.withLock {
            val tokens = store.load() ?: throw notSignedIn()
            // Another caller refreshed while we waited for the lock.
            if (tokens.accessToken != seen) tokens.accessToken else refreshLocked(tokens)
        }
    }

    private suspend fun refreshLocked(tokens: Tokens): String {
        val fresh = try {
            client.refresh(tokens)
        } catch (e: AuthException) {
            if (e.isInvalidGrant) store.clear()
            throw e
        }
        store.save(fresh)
        return fresh.accessToken
    }

    private fun notSignedIn() = AuthException("Not signed in to Spotify", 401, "not_signed_in")

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}
