package app.remastered.core.warehouse

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The app's view of the warehouse's answers. Wire shapes (warehouse/Dtos.kt) are mapped onto
 * these at the edge, so screens never see JSON names.
 *
 * Names are as Spotify reported them when the play was stored. Ids are Spotify base-62 ids where
 * the warehouse has one; plays imported from the Extended Streaming History export may carry
 * `name:` placeholder ids instead, so use [spotifyUri] before handing one to the player.
 * Counts that the warehouse estimates (minutes) are rounded to whole minutes.
 */

/** `spotify:<type>:<id>` for a real base-62 id, or null for placeholders and blanks. */
fun spotifyUri(type: String, id: String?): String? = id?.takeIf { SPOTIFY_ID.matches(it) }?.let { "spotify:$type:$it" }

private val SPOTIFY_ID = Regex("[0-9A-Za-z]{22}")

// --- /api/plays, /api/daily, /api/top-artists ---

/** One stored play. [playedAtMs] is when it ended (epoch ms), as Spotify reports it. */
data class Play(
    val playedAtMs: Long,
    val trackId: String,
    val name: String,
    /** Every credited artist, comma-joined in credit order. */
    val artists: String,
    val album: String?,
    val imageUrl: String?,
    val durationMs: Long?,
    /** The playlist, album or artist it was played from, when Spotify said. */
    val contextUri: String?,
    val contextType: String?,
    /** `api` (polled) or `export` (imported history). */
    val source: String?,
    val isrc: String?,
) {
    val trackUri get() = spotifyUri("track", trackId)
    val playedAt: Instant get() = Instant.ofEpochMilli(playedAtMs)
}

/**
 * One page of plays, newest first. [nextBefore] is the warehouse's opaque cursor for the page
 * after it (pass it back as `before`), null once there's nothing older. [cursored] is false for a
 * warehouse too old to send a cursor at all; page those by time instead (see [PlayPager]).
 */
data class PlayPage(val plays: List<Play>, val nextBefore: String?, val cursored: Boolean)

data class DayPlays(val day: LocalDate, val plays: Int)

data class DailyPlays(val from: LocalDate, val to: LocalDate, val timeZone: String, val days: List<DayPlays>)

data class ArtistPlays(val id: String?, val name: String, val plays: Int) {
    val uri get() = spotifyUri("artist", id)
}

data class TopArtists(val from: LocalDate, val to: LocalDate, val timeZone: String, val artists: List<ArtistPlays>)

// --- /api/artist, /api/track, /api/on-this-day ---

data class MonthPlays(val month: YearMonth, val plays: Int)

data class TrackPlays(val id: String?, val name: String, val plays: Int, val imageUrl: String?) {
    val uri get() = spotifyUri("track", id)
}

/** Everything the history knows about one artist. [months] has only months with plays, oldest first. */
data class ArtistHistory(
    val id: String?,
    val name: String,
    val plays: Int,
    val minutes: Int,
    val firstPlayed: Instant?,
    val lastPlayed: Instant?,
    val months: List<MonthPlays>,
    val topTracks: List<TrackPlays>,
) {
    val uri get() = spotifyUri("artist", id)
}

data class TrackHistory(
    val id: String?,
    val name: String?,
    val artist: String?,
    val imageUrl: String?,
    val plays: Int,
    val minutes: Int,
    val firstPlayed: Instant?,
    val lastPlayed: Instant?,
    val months: List<MonthPlays>,
) {
    val uri get() = spotifyUri("track", id)
}

data class OnThisDayTrack(val id: String?, val name: String, val artist: String?, val imageUrl: String?, val plays: Int) {
    val uri get() = spotifyUri("track", id)
}

data class OnThisDayYear(val year: Int, val plays: Int, val topTracks: List<OnThisDayTrack>)

data class OnThisDay(val date: LocalDate, val years: List<OnThisDayYear>)

// --- /api/home ---

/**
 * A playlist, album or artist the warehouse ranked for Home. [name] and [imageUrl] are null when
 * it doesn't know them (playlists and artists have no image there): look those up. [artist] is an
 * album's or artist page's primary artist. [sessions] is how often it was played from (for
 * Rediscover, on how many days).
 */
data class HomeEntry(
    val uri: String,
    val type: String,
    val name: String?,
    val imageUrl: String?,
    val artist: String?,
    val sessions: Int,
    val lastPlayedMs: Long?,
)

/**
 * Home's rows from the history. Rows can overlap: show each entry once, in this order.
 * [hour] is the local hour the rows were ranked for.
 */
data class HomeFeed(
    val hour: Int?,
    val shortcuts: List<HomeEntry>,
    val jumpBackIn: List<HomeEntry>,
    val aroundNow: List<HomeEntry>,
    val rediscover: List<HomeEntry>,
)

// --- /api/stats/on-repeat ---

data class RepeatTrack(
    val trackId: String,
    val name: String,
    val artists: String,
    val album: String?,
    val imageUrl: String?,
    val plays: Int,
    /** Days it was played in the window. */
    val days: Int,
    /** Plays per local day over the window, oldest first; the last is yesterday. */
    val daily: List<Int>,
) {
    val uri get() = spotifyUri("track", trackId)
}

data class RepeatAlbum(val name: String, val artist: String, val imageUrl: String?, val tracksOnRepeat: Int, val plays: Int)

/**
 * Songs with 5+ plays on 3+ of the last [windowDays] days, at least twice their usual rate (one
 * per album), and albums with 4+ such songs. Covers whole days up to [asOf] (local midnight).
 */
data class OnRepeat(val asOf: Instant?, val windowDays: Int, val tracks: List<RepeatTrack>, val albums: List<RepeatAlbum>)

// --- /api/stats/rhythm ---

/** Runs of consecutive days with at least a threshold of plays. Dates are months only. */
data class Streak(
    /** Exact length of a finished run; null while it's still running (then only [weeks] is given). */
    val days: Int?,
    val weeks: Int,
    val from: YearMonth?,
    val to: YearMonth?,
    val running: Boolean,
)

data class StreakSet(
    /** Plays a day needs to count: 1, 10 or 25. */
    val threshold: Int,
    val currentWeeks: Int,
    val longest: Streak?,
    val top: List<Streak>,
    /** Share (0–1) of days in the range with at least [threshold] plays. */
    val activeShare: Double,
)

/** Listening by time of day in one era (where the user lived), in its local time. */
data class EraRhythm(
    val key: String,
    val label: String,
    val from: YearMonth?,
    /** Null while the era is still going. */
    val to: YearMonth?,
    /** Share of the era's plays in each local hour 0–23; null until the era spans four weeks. */
    val hourShare: List<Double>?,
    /** Monday-first 7 × 24 shares; null until the era spans 26 weeks. */
    val grid: List<List<Double>>?,
    val hoursAvailableFrom: YearMonth?,
    val gridAvailableFrom: YearMonth?,
)

data class RhythmYear(val year: Int, val partial: Boolean, val streaks: List<StreakSet>, val eras: List<EraRhythm>)

/** Streaks and time of day, all time and per year. Data runs up to (not including) [asOf], the start of this week. */
data class Rhythm(
    val asOf: Instant?,
    val from: YearMonth?,
    val to: YearMonth?,
    val streaks: List<StreakSet>,
    val eras: List<EraRhythm>,
    val years: List<RhythmYear>,
)

// --- /api/stats/top-over-time ---

data class RankedArtist(val name: String, val plays: Int, val share: Double)

data class RankedTrack(val name: String, val artist: String, val plays: Int, val imageUrl: String?)

/** A quarter (`2026-Q3`) or a year (`2026`). */
data class Period(val period: String, val partial: Boolean, val plays: Int, val artists: List<RankedArtist>, val tracks: List<RankedTrack>)

/** One of the most-played artists overall; [share] lines up with [TopOverTime.quarters]. */
data class Leader(val name: String, val plays: Int, val share: List<Double>)

/** Who led each quarter and year (primary artist only). [leaders] are ordered by when they peaked. */
data class TopOverTime(val asOf: Instant?, val quarters: List<Period>, val years: List<Period>, val leaders: List<Leader>)

// --- /api/stats/eras ---

data class MonthRange(val from: YearMonth?, val to: YearMonth?)

data class ArtistShare(val artist: String, val share: Double)

data class ArtistShift(val artist: String, val before: Double, val after: Double)

data class TypicalChange(val median: Double, val min: Double, val max: Double, val windows: Int, val exceededBy: Int)

data class DriftSummary(
    /** Share of plays since the move that went to artists already played before it. */
    val knownArtistShare: Double,
    val newArtists: Int,
    val artists: Int,
    /** Total variation distance (0–1) between the windows either side of the move. */
    val change: Double,
    /** The same measure between back-to-back windows of the same length before the move. */
    val typicalChange: TypicalChange,
)

/**
 * Artist drift across the move from Newcastle to Singapore: the [windowMonths] whole months
 * after it ([singapore]) against as many just before it ([before]). [summary] is null until [ready].
 */
data class Eras(
    val asOf: Instant?,
    val moveMonth: YearMonth?,
    val windowMonths: Int,
    val singapore: MonthRange?,
    val before: MonthRange?,
    val ready: Boolean,
    val summary: DriftSummary?,
    /** Top artists of the whole Newcastle era. */
    val topNewcastle: List<ArtistShare>,
    val topBefore: List<ArtistShare>,
    val topSingapore: List<ArtistShare>,
    val risers: List<ArtistShift>,
    val fallers: List<ArtistShift>,
)

// --- /api/stats/albums ---

data class TopAlbum(
    val name: String,
    val artist: String,
    val imageUrl: String?,
    val plays: Int,
    /** Different songs played from it. */
    val songs: Int,
    val from: YearMonth?,
    val to: YearMonth?,
)

data class AlbumYear(val year: Int, val partial: Boolean, val albums: List<TopAlbum>)

/** Most-played albums with at least [minSongs] different songs played, all time and per year. */
data class TopAlbums(val asOf: Instant?, val minSongs: Int, val albums: List<TopAlbum>, val years: List<AlbumYear>)

// --- /api/wrapped, /api/wrapped/ranges ---

data class WrappedRange(
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
    val kind: Kind,
    /** Already computed: a read token can open it. */
    val computed: Boolean,
) {
    enum class Kind { Year, YearToDate, Other }
}

data class WrappedTotals(val minutes: Int, val plays: Int, val skips: Int, val daysListened: Int, val artists: Int, val songs: Int)

data class WrappedArtist(
    val name: String,
    val minutes: Int,
    val plays: Int,
    /** Share (0–1) of all minutes in the range. */
    val share: Double,
    val days: Int,
    val longestRunDays: Int,
    val topSong: String?,
    /** Art of their top song: the warehouse has no artist photos. */
    val imageUrl: String?,
)

data class WrappedSong(
    val name: String,
    val artist: String,
    val album: String?,
    val imageUrl: String?,
    val plays: Int,
    val minutes: Int,
    val peakMonth: YearMonth?,
    val firstPlayed: LocalDate?,
)

data class WrappedAlbum(val name: String, val artist: String, val imageUrl: String?, val plays: Int, val minutes: Int, val songs: Int)

data class WrappedMonth(val month: YearMonth, val minutes: Int, val topArtist: String?, val topSong: String?, val imageUrl: String?)

data class Discovery(val name: String, val minutes: Int, val imageUrl: String?)

/** Artists and songs first played (ever) inside the range. */
data class Discoveries(val artists: Int, val songs: Int, val share: Double, val top: List<Discovery>)

data class DecadeShare(val decade: Int, val share: Double)

/** The age of someone whose late teens fall in the middle of [window], the release years played most. */
data class ListeningAge(val age: Int, val medianYear: Int, val window: IntRange?, val coverage: Double, val decades: List<DecadeShare>)

data class DayRun(val days: Int, val from: LocalDate, val to: LocalDate)

data class BiggestDay(val date: LocalDate, val minutes: Int)

/** A Spotify-Wrapped-style summary of [from]..[to] (local days, inclusive). Minutes are estimates. */
data class Wrapped(
    val from: LocalDate,
    val to: LocalDate,
    val days: Int,
    val computedAt: Instant?,
    val totals: WrappedTotals,
    val biggestDay: BiggestDay?,
    /** Minutes in each local hour 0–23. */
    val hours: List<Int>,
    /** Minutes on each weekday, Monday first. */
    val weekdays: List<Int>,
    val topArtists: List<WrappedArtist>,
    /** Ranked by plays, as Spotify's are; artists and albums by minutes. */
    val topSongs: List<WrappedSong>,
    val topAlbums: List<WrappedAlbum>,
    val months: List<WrappedMonth>,
    val discoveries: Discoveries,
    /** Null when under half the minutes have a release year. */
    val listeningAge: ListeningAge?,
    /** Longest run of consecutive days with any listening. */
    val streak: DayRun?,
)
