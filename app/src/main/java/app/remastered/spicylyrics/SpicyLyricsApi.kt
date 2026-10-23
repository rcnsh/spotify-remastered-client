package app.remastered.spicylyrics

import app.remastered.core.lyrics.LyricsResult.Problem
import app.remastered.spotify.web.await
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * `GET /v1/lyrics/{trackId}` on the Spicy Lyrics developer API, with a publishable key
 * (`sl_pk_…`, "No origin header" allowed). OkHttp sends no Origin header, which is what that
 * option expects. The key is never logged. The direct [LyricsEndpoint], used when the warehouse
 * doesn't serve lyrics and this build has a key.
 */
class SpicyLyricsApi(
    private val http: OkHttpClient,
    private val key: String,
    baseUrl: String = "https://api.spicylyrics.org/",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsEndpoint {
    /** A Spicy Lyrics answer, from either [LyricsEndpoint]. */
    sealed interface Response {
        /** [ageMs]: how long ago it was fetched from Spicy Lyrics (the warehouse may have kept it). */
        data class Body(val json: String, val ageMs: Long = 0) : Response
        data class NotFound(val ageMs: Long = 0) : Response
        data class Failed(val problem: Problem, val retryAfterMs: Long? = null) : Response
        /** This endpoint doesn't serve lyrics (a warehouse without the route): ask the next one. */
        data object Unavailable : Response
    }

    private val base: HttpUrl = baseUrl.toHttpUrl()

    val isConfigured get() = key.isNotBlank()

    override val via = LyricsEndpoint.Via.Direct

    override suspend fun fetch(trackId: String): Response = withContext(io) {
        require(isTrackId(trackId)) { "Not a Spotify track id" }
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/lyrics").addPathSegment(trackId).build())
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(request).await().use { it.toResponse() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Response.Failed(Problem.Offline)
        }
    }

    private fun okhttp3.Response.toResponse(): Response = when (code) {
        200 -> Response.Body(body.string())
        404 -> Response.NotFound()
        401, 403 -> Response.Failed(Problem.KeyRejected)
        429 -> Response.Failed(Problem.RateLimited, retryAfterMs())
        503 -> Response.Failed(Problem.ServiceDown, retryAfterMs())
        else -> Response.Failed(Problem.ServiceDown)
    }

    companion object {
        private val TRACK_ID = Regex("[0-9A-Za-z]{22}")

        fun isTrackId(id: String) = TRACK_ID.matches(id)

        /** `Retry-After`, else `RateLimit-Reset` (both seconds), in ms. */
        internal fun okhttp3.Response.retryAfterMs(): Long? =
            (header("Retry-After") ?: header("RateLimit-Reset"))?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}
