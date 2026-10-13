package app.remastered.warehouse

import app.remastered.core.warehouse.ArtistHistory
import app.remastered.core.warehouse.DailyPlays
import app.remastered.core.warehouse.Eras
import app.remastered.core.warehouse.OnRepeat
import app.remastered.core.warehouse.OnThisDay
import app.remastered.core.warehouse.Play
import app.remastered.core.warehouse.Rhythm
import app.remastered.core.warehouse.TopAlbums
import app.remastered.core.warehouse.TopArtists
import app.remastered.core.warehouse.TopOverTime
import app.remastered.core.warehouse.TrackHistory
import app.remastered.core.warehouse.Warehouse
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.warehouse.WrappedRange
import app.remastered.spotify.web.await
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [Warehouse] over music-warehouse's read API, with the bearer token from [config] (read per
 * call, so a change in Settings applies to the next request). The token goes in the
 * Authorization header only, which [app.remastered.spotify.web.RequestLog] never records.
 *
 * A 503 with `Retry-After` means a stat is being computed: it's retried up to [MAX_BUSY_RETRIES]
 * times, waiting as asked (at most [MAX_RETRY_AFTER_S] each), and the wait is cancelled with the
 * caller. Nothing else is retried. [onOutcome] hears every call's path and result (null for
 * success) so [WarehouseServices] can keep its state current. [sleep] is injectable so tests
 * don't wait.
 */
class WarehouseApi(
    private val http: OkHttpClient,
    private val config: () -> WarehouseConfig?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onOutcome: (path: String, WarehouseException?) -> Unit = { _, _ -> },
) : Warehouse {

    override suspend fun plays(limit: Int, beforeMs: Long?, afterMs: Long?): List<Play> =
        get<PlaysDto>("api/plays") {
            add("limit", limit.coerceIn(1, 500))
            beforeMs?.let { add("before", it) }
            afterMs?.let { add("after", it) }
        }.plays.mapNotNull { it?.toModel() }

    override suspend fun daily(from: LocalDate, to: LocalDate, zone: ZoneId): DailyPlays =
        get<DailyDto>("api/daily") { range(from, to); add("tz", zone.id) }.toModel(from, to, zone.id)

    override suspend fun topArtists(from: LocalDate, to: LocalDate, zone: ZoneId, limit: Int): TopArtists =
        get<TopArtistsDto>("api/top-artists") { range(from, to); add("tz", zone.id); add("limit", limit.coerceIn(1, 500)) }
            .toModel(from, to, zone.id)

    override suspend fun artist(id: String?, name: String?, zone: ZoneId): ArtistHistory {
        require(!id.isNullOrBlank() || !name.isNullOrBlank()) { "An artist needs an id or a name" }
        return get<ArtistHistoryDto>("api/artist") {
            id?.takeIf { it.isNotBlank() }?.let { add("id", it) }
            name?.takeIf { it.isNotBlank() }?.let { add("name", it) }
            add("tz", zone.id)
        }.toModel()
    }

    override suspend fun track(id: String, zone: ZoneId): TrackHistory =
        get<TrackHistoryDto>("api/track") { add("id", id); add("tz", zone.id) }.toModel()

    override suspend fun onThisDay(date: LocalDate, zone: ZoneId): OnThisDay =
        get<OnThisDayDto>("api/on-this-day") { add("date", date.toString()); add("tz", zone.id) }.toModel(date)

    override suspend fun onRepeat(): OnRepeat = get<OnRepeatDto>("api/stats/on-repeat").toModel()
    override suspend fun rhythm(): Rhythm = get<RhythmDto>("api/stats/rhythm").toModel()
    override suspend fun topOverTime(): TopOverTime = get<TopOverTimeDto>("api/stats/top-over-time").toModel()
    override suspend fun eras(): Eras = get<ErasDto>("api/stats/eras").toModel()
    override suspend fun topAlbums(): TopAlbums = get<TopAlbumsDto>("api/stats/albums").toModel()

    override suspend fun wrappedRanges(): List<WrappedRange> =
        get<WrappedRangesDto>("api/wrapped/ranges").ranges.mapNotNull { it?.toModel() }

    override suspend fun wrapped(from: LocalDate, to: LocalDate): Wrapped =
        get<WrappedDto>("api/wrapped") { range(from, to) }.toModel(from, to)

    /**
     * Whether this deployment serves [feature]: true or false, or null when the warehouse couldn't
     * be reached. Asks with parameters the warehouse rejects (400) before touching its database,
     * except for the ranges list, which is cheap.
     */
    suspend fun serves(feature: WarehouseFeature): Boolean? = try {
        withContext(io) {
            send(feature.path) {
                when (feature) {
                    WarehouseFeature.OnThisDay -> add("date", "-")
                    WarehouseFeature.ArtistHistory, WarehouseFeature.TrackHistory, WarehouseFeature.WrappedRanges -> Unit
                }
            }
        }
        true
    } catch (e: WarehouseException) {
        when (e.problem) {
            Problem.NotFound -> false
            Problem.Unreachable, Problem.NotConfigured -> null
            else -> true
        }
    }

    // --- plumbing ---

    private class Query(private val url: HttpUrl.Builder) {
        fun add(name: String, value: Any) { url.addQueryParameter(name, value.toString()) }
        fun range(from: LocalDate, to: LocalDate) { add("from", from); add("to", to) }
    }

    private suspend inline fun <reified T> get(path: String, noinline query: Query.() -> Unit = {}): T {
        val body = withContext(io) { send(path, query) }
        return try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw report(path, WarehouseException(Problem.BadResponse, "Unexpected response from the warehouse", cause = e))
        } catch (e: IllegalArgumentException) {
            throw report(path, WarehouseException(Problem.BadResponse, "Unexpected response from the warehouse", cause = e))
        }
    }

    /** Returns the body of a 2xx response; throws [WarehouseException] for anything else. */
    private suspend fun send(path: String, query: Query.() -> Unit): String {
        val cfg = config() ?: throw WarehouseException(Problem.NotConfigured, "Listening history isn't set up")
        val url = cfg.baseUrl.toHttpUrl().newBuilder().addPathSegments(path).also { Query(it).query() }.build()
        var busyRetries = 0
        while (true) {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Accept", "application/json")
                .build()
            val reply = try {
                http.newCall(request).await().use { Reply(it.code, it.header("Retry-After"), it.body.string()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw report(path, WarehouseException(Problem.Unreachable, "Can't reach the warehouse (${e.javaClass.simpleName})", cause = e))
            }
            if (reply.code in 200..299) {
                onOutcome(path, null)
                return reply.body
            }
            val retryAfterS = reply.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            if (reply.code == 503 && retryAfterS != null && retryAfterS <= MAX_RETRY_AFTER_S && busyRetries < MAX_BUSY_RETRIES) {
                busyRetries++
                // OkHttp already retried a `Retry-After: 0` once; wait at least a second after that.
                sleep(retryAfterS.coerceAtLeast(1) * 1000)
                continue
            }
            throw report(path, error(reply, retryAfterS))
        }
    }

    private class Reply(val code: Int, val retryAfter: String?, val body: String)

    /** Maps a non-2xx reply; the warehouse's `{"error": "…"}` becomes the message where it's useful. */
    private fun error(reply: Reply, retryAfterS: Long?): WarehouseException {
        val said = try {
            json.decodeFromString<ErrorDto>(reply.body).error
        } catch (_: Exception) {
            null // Not JSON (a Cloudflare error page, say).
        }
        val problem = when {
            reply.code == 400 -> Problem.BadRequest
            reply.code == 401 -> Problem.Unauthorized
            reply.code == 403 -> Problem.Forbidden
            reply.code == 404 -> Problem.NotFound
            reply.code == 429 || (reply.code == 503 && retryAfterS != null) -> Problem.Busy
            reply.code >= 500 -> Problem.ServerError
            else -> Problem.BadRequest
        }
        return WarehouseException(problem, said ?: "The warehouse answered ${reply.code}", reply.code, retryAfterS)
    }

    private fun report(path: String, e: WarehouseException): WarehouseException = e.also { onOutcome(path, it) }

    internal companion object {
        const val MAX_BUSY_RETRIES = 12
        const val MAX_RETRY_AFTER_S = 30L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
