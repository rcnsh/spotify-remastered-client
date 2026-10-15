package app.remastered.core.warehouse

import java.time.LocalDate
import java.time.ZoneId

/**
 * The user's own play history, from their music-warehouse Worker (github.com/rcnsh/music-warehouse).
 * Implemented by [app.remastered.warehouse.WarehouseApi]; reached through
 * [app.remastered.warehouse.WarehouseServices], which also says whether it's set up at all.
 *
 * Every method may throw [WarehouseException]. `zone` buckets plays into local calendar days;
 * the stats routes and Wrapped use the warehouse's own per-era zones and take none.
 */
interface Warehouse {
    // History
    /** Newest first, plays strictly before [beforeMs]. To page through, use [playsPage]. [limit] is 1–500. */
    suspend fun plays(limit: Int = 50, beforeMs: Long? = null, afterMs: Long? = null): List<Play>
    /**
     * A page of plays, newest first, before [before]: a previous page's [PlayPage.nextBefore], or an
     * epoch ms as a string. The cursor keeps plays that share a millisecond from being skipped.
     */
    suspend fun playsPage(limit: Int = 50, before: String? = null): PlayPage
    /** Plays per local day, every day in the range included (quiet days are 0). At most 3,660 days. */
    suspend fun daily(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailyPlays
    /** Every artist on a track counts, not just the lead. [limit] is 1–500. */
    suspend fun topArtists(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault(), limit: Int = 50): TopArtists

    // Pages built from history
    /** By id, by name, or both (placeholder ids from the export are folded by name). */
    suspend fun artist(id: String? = null, name: String? = null, zone: ZoneId = ZoneId.systemDefault()): ArtistHistory
    suspend fun track(id: String, zone: ZoneId = ZoneId.systemDefault()): TrackHistory
    /** The same calendar day in earlier years, newest year first. */
    suspend fun onThisDay(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): OnThisDay
    /** Home's rows, ranked from the whole history for this local hour. */
    suspend fun home(zone: ZoneId = ZoneId.systemDefault()): HomeFeed

    // Stats (cached by the warehouse; a cold one may take a few seconds)
    suspend fun onRepeat(): OnRepeat
    suspend fun rhythm(): Rhythm
    suspend fun topOverTime(): TopOverTime
    suspend fun eras(): Eras
    suspend fun topAlbums(): TopAlbums

    // Wrapped
    /** Ranges worth offering, with whether each has been computed (a read token can only open those). */
    suspend fun wrappedRanges(): List<WrappedRange>
    /**
     * A year-in-review for a closed range ending before today. With a read token, a preset range
     * from [wrappedRanges] is computed on first open (it can take a while); any other range nobody
     * computed yet fails with [WarehouseException.Problem.Forbidden].
     */
    suspend fun wrapped(from: LocalDate, to: LocalDate): Wrapped
}

/**
 * Routes added to the warehouse after the first read API (warehouse PR #5; Home later). An older deployment
 * answers 404 for them; [app.remastered.warehouse.WarehouseServices.missing] says which, so the
 * features built on them hide instead of failing.
 */
enum class WarehouseFeature(internal val path: String) {
    ArtistHistory("api/artist"),
    TrackHistory("api/track"),
    OnThisDay("api/on-this-day"),
    WrappedRanges("api/wrapped/ranges"),
    Home("api/home"),
}

/** A warehouse failure the UI can show. [status] is the HTTP status, or null when there wasn't one. */
class WarehouseException(
    val problem: Problem,
    message: String,
    val status: Int? = null,
    /** Seconds the warehouse asked us to wait (503 while a stat is being computed). */
    val retryAfterS: Long? = null,
    cause: Throwable? = null,
    /** A 503 because the warehouse's Spotify grant lapsed: its owner must re-authorize it; retrying won't help. */
    val needsReauth: Boolean = false,
) : Exception(message, cause) {
    enum class Problem {
        /** No URL or token set in Settings. Nothing was sent. */
        NotConfigured,
        /** 401: the token is wrong, was rotated or revoked, or expired (signed-in tokens last 90 days). */
        Unauthorized,
        /** 403: the token may not do this (a read token asking for an uncomputed Wrapped range). */
        Forbidden,
        /** 404: no such route (an older warehouse) or nothing with that id. */
        NotFound,
        /** 400: the warehouse refused the parameters; [message] has its reason. */
        BadRequest,
        /** 503 that outlasted our retries: the warehouse is still computing. */
        Busy,
        /** Any other 5xx, including a lapsed Spotify grant ([needsReauth]). */
        ServerError,
        /** No response: offline, DNS, TLS, timeout. */
        Unreachable,
        /** A 2xx we couldn't read. */
        BadResponse,
    }

    /** One sentence for a snackbar or an error state. */
    val userMessage: String
        get() = when (problem) {
            Problem.NotConfigured -> "Listening history isn't set up. Add your warehouse in Settings."
            Problem.Unauthorized -> "The warehouse rejected the token, or it expired. Sign in again in Settings."
            Problem.Forbidden -> message ?: "The warehouse token can't open this."
            Problem.NotFound -> "The warehouse has nothing here."
            Problem.BadRequest -> message ?: "The warehouse refused that request."
            Problem.Busy -> "The warehouse is still working this out. Try again in a minute."
            Problem.ServerError ->
                if (needsReauth) "The warehouse's Spotify connection needs re-authorizing by its owner. Stored history still works."
                else "The warehouse had a problem. Try again later."
            Problem.Unreachable -> "Can't reach the warehouse. Check your connection."
            Problem.BadResponse -> "The warehouse sent something this version can't read."
        }
}

/**
 * Whether listening history is available, for gating every warehouse feature (ADR 0004: keep each
 * feature behind a capability check). Updated by every request, so a rotated token shows up as
 * [Unauthorized] wherever it was noticed.
 */
sealed interface WarehouseState {
    /** No URL or token: hide every warehouse feature. */
    data object NotConfigured : WarehouseState
    /** Set up, not yet confirmed by a response. */
    data object Checking : WarehouseState
    /** The last request succeeded. */
    data object Ready : WarehouseState
    /** The token was rejected (401). */
    data object Unauthorized : WarehouseState
    /** The last request got no response (offline, DNS, timeout), or the check got a server error. */
    data class Unreachable(val message: String) : WarehouseState

    /**
     * Show warehouse entry points. True while checking and when offline too: the screens show
     * their own error with a retry. False without config or with a rejected token (Settings says why).
     */
    val isUsable: Boolean get() = this is Checking || this is Ready || this is Unreachable
}
