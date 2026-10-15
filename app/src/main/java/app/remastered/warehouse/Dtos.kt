package app.remastered.warehouse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes of music-warehouse's /api routes (src/api.ts, src/stats/*.ts, src/wrapped.ts there).
// Everything is optional with a default so an added, renamed or missing field degrades one value
// instead of failing the whole response; mapping (Mapping.kt) drops entries without what they need.
// Minutes and shares are Double: the warehouse rounds them today, but a fraction must still decode.

@Serializable
internal data class ErrorDto(val error: String? = null)

// --- /api/plays, /api/daily, /api/top-artists ---

/**
 * [nextBefore] is [NO_CURSOR] when the field is missing (a warehouse from before the cursor) and
 * null when it's there and null (no more pages).
 */
@Serializable
internal data class PlaysDto(
    val plays: List<PlayDto?> = emptyList(),
    @SerialName("next_before") val nextBefore: String? = NO_CURSOR,
)

internal const val NO_CURSOR = "\u0000none"

@Serializable
internal data class PlayDto(
    @SerialName("played_at_ms") val playedAtMs: Long? = null,
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("context_uri") val contextUri: String? = null,
    @SerialName("context_type") val contextType: String? = null,
    val source: String? = null,
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val isrc: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val artists: String? = null,
)

@Serializable
internal data class DailyDto(
    val from: String? = null,
    val to: String? = null,
    val tz: String? = null,
    val days: List<DayDto?> = emptyList(),
)

@Serializable
internal data class DayDto(val day: String? = null, val plays: Int = 0)

@Serializable
internal data class TopArtistsDto(
    val from: String? = null,
    val to: String? = null,
    val tz: String? = null,
    val artists: List<ArtistPlaysDto?> = emptyList(),
)

@Serializable
internal data class ArtistPlaysDto(@SerialName("artist_id") val artistId: String? = null, val name: String? = null, val plays: Int = 0)

// --- /api/artist, /api/track, /api/on-this-day ---

@Serializable
internal data class MonthPlaysDto(val month: String? = null, val plays: Int = 0)

@Serializable
internal data class ArtistRefDto(val id: String? = null, val name: String? = null)

@Serializable
internal data class TrackPlaysDto(val id: String? = null, val name: String? = null, val plays: Int = 0, val image: String? = null)

@Serializable
internal data class ArtistHistoryDto(
    val artist: ArtistRefDto? = null,
    val plays: Int = 0,
    val minutes: Double = 0.0,
    @SerialName("first_played") val firstPlayed: String? = null,
    @SerialName("last_played") val lastPlayed: String? = null,
    val months: List<MonthPlaysDto?> = emptyList(),
    @SerialName("top_tracks") val topTracks: List<TrackPlaysDto?> = emptyList(),
)

@Serializable
internal data class TrackRefDto(val id: String? = null, val name: String? = null, val artist: String? = null, val image: String? = null)

@Serializable
internal data class TrackHistoryDto(
    val track: TrackRefDto? = null,
    val plays: Int = 0,
    val minutes: Double = 0.0,
    @SerialName("first_played") val firstPlayed: String? = null,
    @SerialName("last_played") val lastPlayed: String? = null,
    val months: List<MonthPlaysDto?> = emptyList(),
)

@Serializable
internal data class OnThisDayDto(val date: String? = null, val years: List<OnThisDayYearDto?> = emptyList())

@Serializable
internal data class OnThisDayYearDto(
    val year: Int? = null,
    val plays: Int = 0,
    @SerialName("top_tracks") val topTracks: List<OnThisDayTrackDto?> = emptyList(),
)

@Serializable
internal data class OnThisDayTrackDto(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val image: String? = null,
    val plays: Int = 0,
)

// --- /api/home ---

@Serializable
internal data class HomeDto(
    val hour: Int? = null,
    val shortcuts: List<HomeEntryDto?> = emptyList(),
    @SerialName("jump_back_in") val jumpBackIn: List<HomeEntryDto?> = emptyList(),
    @SerialName("around_now") val aroundNow: List<HomeEntryDto?> = emptyList(),
    val rediscover: List<HomeEntryDto?> = emptyList(),
)

@Serializable
internal data class HomeEntryDto(
    val uri: String? = null,
    val type: String? = null,
    val name: String? = null,
    val image: String? = null,
    val artist: String? = null,
    val sessions: Int = 0,
    @SerialName("last_played_ms") val lastPlayedMs: Long? = null,
)

// --- /api/stats/on-repeat ---

@Serializable
internal data class OnRepeatDto(
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("window_days") val windowDays: Int = 14,
    val tracks: List<RepeatTrackDto?> = emptyList(),
    val albums: List<RepeatAlbumDto?> = emptyList(),
)

@Serializable
internal data class RepeatTrackDto(
    @SerialName("track_id") val trackId: String? = null,
    val name: String? = null,
    val artists: String? = null,
    val album: String? = null,
    val image: String? = null,
    val plays: Int = 0,
    val days: Int = 0,
    val daily: List<Int?> = emptyList(),
)

@Serializable
internal data class RepeatAlbumDto(
    val name: String? = null,
    val artist: String? = null,
    val image: String? = null,
    @SerialName("tracks_on_repeat") val tracksOnRepeat: Int = 0,
    val plays: Int = 0,
)

// --- /api/stats/rhythm ---

@Serializable
internal data class RhythmDto(
    @SerialName("as_of") val asOf: String? = null,
    val range: MonthRangeDto? = null,
    val streaks: List<StreakSetDto?> = emptyList(),
    val eras: List<EraRhythmDto?> = emptyList(),
    val years: List<RhythmYearDto?> = emptyList(),
)

@Serializable
internal data class MonthRangeDto(val from: String? = null, val to: String? = null)

@Serializable
internal data class StreakDto(
    val days: Int? = null,
    val weeks: Int = 0,
    val from: String? = null,
    val to: String? = null,
    val running: Boolean = false,
)

@Serializable
internal data class StreakSetDto(
    val threshold: Int? = null,
    @SerialName("current_weeks") val currentWeeks: Int = 0,
    val longest: StreakDto? = null,
    val top: List<StreakDto?> = emptyList(),
    @SerialName("active_share") val activeShare: Double = 0.0,
)

@Serializable
internal data class EraRhythmDto(
    val key: String? = null,
    val label: String? = null,
    val from: String? = null,
    val to: String? = null,
    @SerialName("hour_share") val hourShare: List<Double?>? = null,
    val grid: List<List<Double?>?>? = null,
    @SerialName("hours_available_from") val hoursAvailableFrom: String? = null,
    @SerialName("grid_available_from") val gridAvailableFrom: String? = null,
)

@Serializable
internal data class RhythmYearDto(
    val year: String? = null,
    val partial: Boolean = false,
    val streaks: List<StreakSetDto?> = emptyList(),
    val eras: List<EraRhythmDto?> = emptyList(),
)

// --- /api/stats/top-over-time ---

@Serializable
internal data class TopOverTimeDto(
    @SerialName("as_of") val asOf: String? = null,
    val quarters: List<PeriodDto?> = emptyList(),
    val years: List<PeriodDto?> = emptyList(),
    val leaders: List<LeaderDto?> = emptyList(),
)

@Serializable
internal data class PeriodDto(
    val period: String? = null,
    val partial: Boolean = false,
    val plays: Int = 0,
    val artists: List<RankedArtistDto?> = emptyList(),
    val tracks: List<RankedTrackDto?> = emptyList(),
)

@Serializable
internal data class RankedArtistDto(val name: String? = null, val plays: Int = 0, val share: Double = 0.0)

@Serializable
internal data class RankedTrackDto(val name: String? = null, val artist: String? = null, val plays: Int = 0, val image: String? = null)

@Serializable
internal data class LeaderDto(val name: String? = null, val plays: Int = 0, val share: List<Double?> = emptyList())

// --- /api/stats/eras ---

@Serializable
internal data class ErasDto(
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("move_month") val moveMonth: String? = null,
    @SerialName("window_months") val windowMonths: Int = 0,
    val singapore: MonthRangeDto? = null,
    val before: MonthRangeDto? = null,
    val ready: Boolean = false,
    val summary: DriftSummaryDto? = null,
    val top: DriftTopDto? = null,
    val risers: List<ArtistShiftDto?> = emptyList(),
    val fallers: List<ArtistShiftDto?> = emptyList(),
)

@Serializable
internal data class DriftSummaryDto(
    @SerialName("known_artist_share") val knownArtistShare: Double = 0.0,
    @SerialName("new_artists") val newArtists: Int = 0,
    val artists: Int = 0,
    val change: Double = 0.0,
    @SerialName("typical_change") val typicalChange: TypicalChangeDto? = null,
)

@Serializable
internal data class TypicalChangeDto(
    val median: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val windows: Int = 0,
    @SerialName("exceeded_by") val exceededBy: Int = 0,
)

@Serializable
internal data class DriftTopDto(
    val newcastle: List<ArtistShareDto?> = emptyList(),
    val before: List<ArtistShareDto?> = emptyList(),
    val singapore: List<ArtistShareDto?> = emptyList(),
)

@Serializable
internal data class ArtistShareDto(val artist: String? = null, val share: Double = 0.0)

@Serializable
internal data class ArtistShiftDto(val artist: String? = null, val before: Double = 0.0, val after: Double = 0.0)

// --- /api/stats/albums ---

@Serializable
internal data class TopAlbumsDto(
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("min_songs") val minSongs: Int = 0,
    val albums: List<TopAlbumDto?> = emptyList(),
    val years: List<AlbumYearDto?> = emptyList(),
)

@Serializable
internal data class TopAlbumDto(
    val name: String? = null,
    val artist: String? = null,
    val image: String? = null,
    val plays: Int = 0,
    val songs: Int = 0,
    val from: String? = null,
    val to: String? = null,
)

@Serializable
internal data class AlbumYearDto(val year: String? = null, val partial: Boolean = false, val albums: List<TopAlbumDto?> = emptyList())

// --- /api/wrapped, /api/wrapped/ranges ---

@Serializable
internal data class WrappedRangesDto(val ranges: List<WrappedRangeDto?> = emptyList())

@Serializable
internal data class WrappedRangeDto(
    val from: String? = null,
    val to: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val computed: Boolean = false,
)

@Serializable
internal data class WrappedDto(
    val range: WrappedRangeSpanDto? = null,
    @SerialName("computed_at") val computedAt: String? = null,
    val totals: WrappedTotalsDto? = null,
    @SerialName("biggest_day") val biggestDay: BiggestDayDto? = null,
    val hours: List<Double?> = emptyList(),
    val weekdays: List<Double?> = emptyList(),
    @SerialName("top_artists") val topArtists: List<WrappedArtistDto?> = emptyList(),
    @SerialName("top_songs") val topSongs: List<WrappedSongDto?> = emptyList(),
    @SerialName("top_albums") val topAlbums: List<WrappedAlbumDto?> = emptyList(),
    val months: List<WrappedMonthDto?> = emptyList(),
    val discoveries: DiscoveriesDto? = null,
    @SerialName("listening_age") val listeningAge: ListeningAgeDto? = null,
    val streak: DayRunDto? = null,
)

@Serializable
internal data class WrappedRangeSpanDto(val from: String? = null, val to: String? = null, val days: Int = 0)

@Serializable
internal data class WrappedTotalsDto(
    val minutes: Double = 0.0,
    val plays: Int = 0,
    val skips: Int = 0,
    @SerialName("days_listened") val daysListened: Int = 0,
    val artists: Int = 0,
    val songs: Int = 0,
)

@Serializable
internal data class BiggestDayDto(val date: String? = null, val minutes: Double = 0.0)

@Serializable
internal data class WrappedArtistDto(
    val name: String? = null,
    val minutes: Double = 0.0,
    val plays: Int = 0,
    val share: Double = 0.0,
    val days: Int = 0,
    @SerialName("longest_run_days") val longestRunDays: Int = 0,
    @SerialName("top_song") val topSong: String? = null,
    val image: String? = null,
)

@Serializable
internal data class WrappedSongDto(
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val image: String? = null,
    val plays: Int = 0,
    val minutes: Double = 0.0,
    @SerialName("peak_month") val peakMonth: String? = null,
    @SerialName("first_played") val firstPlayed: String? = null,
)

@Serializable
internal data class WrappedAlbumDto(
    val name: String? = null,
    val artist: String? = null,
    val image: String? = null,
    val plays: Int = 0,
    val minutes: Double = 0.0,
    val songs: Int = 0,
)

@Serializable
internal data class WrappedMonthDto(
    val month: String? = null,
    val minutes: Double = 0.0,
    @SerialName("top_artist") val topArtist: String? = null,
    @SerialName("top_song") val topSong: String? = null,
    val image: String? = null,
)

@Serializable
internal data class DiscoveriesDto(
    val artists: Int = 0,
    val songs: Int = 0,
    val share: Double = 0.0,
    val top: List<DiscoveryDto?> = emptyList(),
)

@Serializable
internal data class DiscoveryDto(val name: String? = null, val minutes: Double = 0.0, val image: String? = null)

@Serializable
internal data class ListeningAgeDto(
    val age: Int? = null,
    @SerialName("median_year") val medianYear: Int = 0,
    val window: List<Int?> = emptyList(),
    val coverage: Double = 0.0,
    val decades: List<DecadeShareDto?> = emptyList(),
)

@Serializable
internal data class DecadeShareDto(val decade: Int? = null, val share: Double = 0.0)

@Serializable
internal data class DayRunDto(val days: Int = 0, val from: String? = null, val to: String? = null)
