package app.remastered.spotify.web

/**
 * Supplies Web API access tokens to [WebCatalog].
 *
 * Implementations throw if the user isn't signed in or refresh fails; [WebCatalog] maps that to a
 * `CatalogException` with status 401 (or null status for an `IOException`).
 */
interface TokenProvider {
    /** A token that is valid now, refreshing first if it is about to expire. */
    suspend fun accessToken(): String

    /** Forces a refresh, e.g. after a 401, and returns the new token. */
    suspend fun refresh(): String
}
