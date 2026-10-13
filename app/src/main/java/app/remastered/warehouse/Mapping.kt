package app.remastered.warehouse

import app.remastered.core.warehouse.AlbumYear
import app.remastered.core.warehouse.ArtistHistory
import app.remastered.core.warehouse.ArtistPlays
import app.remastered.core.warehouse.ArtistShare
import app.remastered.core.warehouse.ArtistShift
import app.remastered.core.warehouse.BiggestDay
import app.remastered.core.warehouse.DailyPlays
import app.remastered.core.warehouse.DayPlays
import app.remastered.core.warehouse.DayRun
import app.remastered.core.warehouse.DecadeShare
import app.remastered.core.warehouse.Discoveries
import app.remastered.core.warehouse.Discovery
import app.remastered.core.warehouse.DriftSummary
import app.remastered.core.warehouse.EraRhythm
import app.remastered.core.warehouse.Eras
import app.remastered.core.warehouse.Leader
import app.remastered.core.warehouse.ListeningAge
import app.remastered.core.warehouse.MonthPlays
import app.remastered.core.warehouse.MonthRange
import app.remastered.core.warehouse.OnRepeat
import app.remastered.core.warehouse.OnThisDay
import app.remastered.core.warehouse.OnThisDayTrack
import app.remastered.core.warehouse.OnThisDayYear
import app.remastered.core.warehouse.Period
import app.remastered.core.warehouse.Play
import app.remastered.core.warehouse.RankedArtist
import app.remastered.core.warehouse.RankedTrack
import app.remastered.core.warehouse.RepeatAlbum
import app.remastered.core.warehouse.RepeatTrack
import app.remastered.core.warehouse.Rhythm
import app.remastered.core.warehouse.RhythmYear
import app.remastered.core.warehouse.Streak
import app.remastered.core.warehouse.StreakSet
import app.remastered.core.warehouse.TopAlbum
import app.remastered.core.warehouse.TopAlbums
import app.remastered.core.warehouse.TopArtists
import app.remastered.core.warehouse.TopOverTime
import app.remastered.core.warehouse.TrackHistory
import app.remastered.core.warehouse.TrackPlays
import app.remastered.core.warehouse.TypicalChange
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.warehouse.WrappedAlbum
import app.remastered.core.warehouse.WrappedArtist
import app.remastered.core.warehouse.WrappedMonth
import app.remastered.core.warehouse.WrappedRange
import app.remastered.core.warehouse.WrappedSong
import app.remastered.core.warehouse.WrappedTotals
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

// Wire → model. Entries missing what identifies them (a name, a date) are dropped; anything
// else missing takes a neutral value. Dates that don't parse become null rather than failing.

internal fun PlayDto.toModel(): Play? {
    val at = playedAtMs ?: return null
    val id = trackId?.takeIf { it.isNotBlank() } ?: return null
    return Play(at, id, trackName.orEmpty(), artists.orEmpty(), albumName, imageUrl, durationMs, contextUri, contextType, source, isrc)
}

internal fun DailyDto.toModel(from: LocalDate, to: LocalDate, zone: String) = DailyPlays(
    from = day(this.from) ?: from,
    to = day(this.to) ?: to,
    timeZone = tz ?: zone,
    days = days.mapNotNull { d -> d?.let { day(it.day)?.let { at -> DayPlays(at, it.plays) } } },
)

internal fun TopArtistsDto.toModel(from: LocalDate, to: LocalDate, zone: String) = TopArtists(
    from = day(this.from) ?: from,
    to = day(this.to) ?: to,
    timeZone = tz ?: zone,
    artists = artists.mapNotNull { a -> a?.name?.let { ArtistPlays(a.artistId, it, a.plays) } },
)

internal fun ArtistHistoryDto.toModel(): ArtistHistory {
    val name = artist?.name ?: throw malformed("artist")
    return ArtistHistory(
        id = artist.id, name = name, plays = plays, minutes = minutes.roundToInt(),
        firstPlayed = instant(firstPlayed), lastPlayed = instant(lastPlayed),
        months = months.mapNotNull { it?.toModel() },
        topTracks = topTracks.mapNotNull { t -> t?.name?.let { TrackPlays(t.id, it, t.plays, t.image) } },
    )
}

internal fun TrackHistoryDto.toModel() = TrackHistory(
    id = track?.id, name = track?.name, artist = track?.artist, imageUrl = track?.image,
    plays = plays, minutes = minutes.roundToInt(),
    firstPlayed = instant(firstPlayed), lastPlayed = instant(lastPlayed),
    months = months.mapNotNull { it?.toModel() },
)

private fun MonthPlaysDto.toModel(): MonthPlays? = month(month)?.let { MonthPlays(it, plays) }

internal fun OnThisDayDto.toModel(asked: LocalDate) = OnThisDay(
    date = day(date) ?: asked,
    years = years.mapNotNull { y ->
        val year = y?.year ?: return@mapNotNull null
        OnThisDayYear(year, y.plays, y.topTracks.mapNotNull { t -> t?.name?.let { OnThisDayTrack(t.id, it, t.artist, t.image, t.plays) } })
    },
)

internal fun OnRepeatDto.toModel() = OnRepeat(
    asOf = instant(asOf),
    windowDays = windowDays,
    tracks = tracks.mapNotNull { t ->
        val id = t?.trackId ?: return@mapNotNull null
        val name = t.name ?: return@mapNotNull null
        RepeatTrack(id, name, t.artists.orEmpty(), t.album, t.image, t.plays, t.days, t.daily.map { it ?: 0 })
    },
    albums = albums.mapNotNull { a -> a?.name?.let { RepeatAlbum(it, a.artist.orEmpty(), a.image, a.tracksOnRepeat, a.plays) } },
)

internal fun RhythmDto.toModel() = Rhythm(
    asOf = instant(asOf),
    from = month(range?.from),
    to = month(range?.to),
    streaks = streaks.mapNotNull { it?.toModel() },
    eras = eras.mapNotNull { it?.toModel() },
    years = years.mapNotNull { y ->
        val year = y?.year?.toIntOrNull() ?: return@mapNotNull null
        RhythmYear(year, y.partial, y.streaks.mapNotNull { it?.toModel() }, y.eras.mapNotNull { it?.toModel() })
    },
)

private fun StreakSetDto.toModel(): StreakSet? {
    val threshold = threshold ?: return null
    return StreakSet(threshold, currentWeeks, longest?.toModel(), top.mapNotNull { it?.toModel() }, activeShare)
}

private fun StreakDto.toModel() = Streak(days, weeks, month(from), month(to), running)

private fun EraRhythmDto.toModel(): EraRhythm? {
    val key = key ?: return null
    return EraRhythm(
        key = key, label = label ?: key, from = month(from), to = month(to),
        hourShare = hourShare?.map { it ?: 0.0 },
        grid = grid?.map { row -> row.orEmpty().map { it ?: 0.0 } },
        hoursAvailableFrom = month(hoursAvailableFrom),
        gridAvailableFrom = month(gridAvailableFrom),
    )
}

internal fun TopOverTimeDto.toModel() = TopOverTime(
    asOf = instant(asOf),
    quarters = quarters.mapNotNull { it?.toModel() },
    years = years.mapNotNull { it?.toModel() },
    leaders = leaders.mapNotNull { l -> l?.name?.let { Leader(it, l.plays, l.share.map { s -> s ?: 0.0 }) } },
)

private fun PeriodDto.toModel(): Period? {
    val period = period ?: return null
    return Period(
        period, partial, plays,
        artists.mapNotNull { a -> a?.name?.let { RankedArtist(it, a.plays, a.share) } },
        tracks.mapNotNull { t -> t?.name?.let { RankedTrack(it, t.artist.orEmpty(), t.plays, t.image) } },
    )
}

internal fun ErasDto.toModel() = Eras(
    asOf = instant(asOf),
    moveMonth = month(moveMonth),
    windowMonths = windowMonths,
    singapore = singapore?.let { MonthRange(month(it.from), month(it.to)) },
    before = before?.let { MonthRange(month(it.from), month(it.to)) },
    ready = ready,
    summary = summary?.let { s ->
        val t = s.typicalChange ?: TypicalChangeDto()
        DriftSummary(s.knownArtistShare, s.newArtists, s.artists, s.change, TypicalChange(t.median, t.min, t.max, t.windows, t.exceededBy))
    },
    topNewcastle = top?.newcastle.shares(),
    topBefore = top?.before.shares(),
    topSingapore = top?.singapore.shares(),
    risers = risers.shifts(),
    fallers = fallers.shifts(),
)

private fun List<ArtistShareDto?>?.shares() = orEmpty().mapNotNull { a -> a?.artist?.let { ArtistShare(it, a.share) } }

private fun List<ArtistShiftDto?>.shifts() = mapNotNull { a -> a?.artist?.let { ArtistShift(it, a.before, a.after) } }

internal fun TopAlbumsDto.toModel() = TopAlbums(
    asOf = instant(asOf),
    minSongs = minSongs,
    albums = albums.mapNotNull { it?.toModel() },
    years = years.mapNotNull { y ->
        val year = y?.year?.toIntOrNull() ?: return@mapNotNull null
        AlbumYear(year, y.partial, y.albums.mapNotNull { it?.toModel() })
    },
)

private fun TopAlbumDto.toModel(): TopAlbum? {
    val name = name ?: return null
    return TopAlbum(name, artist.orEmpty(), image, plays, songs, month(from), month(to))
}

internal fun WrappedRangeDto.toModel(): WrappedRange? {
    val from = day(from) ?: return null
    val to = day(to) ?: return null
    val kind = when (kind) {
        "year" -> WrappedRange.Kind.Year
        "year_to_date" -> WrappedRange.Kind.YearToDate
        else -> WrappedRange.Kind.Other
    }
    return WrappedRange(from, to, label ?: "$from – $to", kind, computed)
}

internal fun WrappedDto.toModel(askedFrom: LocalDate, askedTo: LocalDate): Wrapped {
    val totals = totals ?: throw malformed("Wrapped")
    return Wrapped(
        from = day(range?.from) ?: askedFrom,
        to = day(range?.to) ?: askedTo,
        days = range?.days?.takeIf { it > 0 } ?: (askedTo.toEpochDay() - askedFrom.toEpochDay() + 1).toInt(),
        computedAt = instant(computedAt),
        totals = WrappedTotals(totals.minutes.roundToInt(), totals.plays, totals.skips, totals.daysListened, totals.artists, totals.songs),
        biggestDay = biggestDay?.let { b -> day(b.date)?.let { BiggestDay(it, b.minutes.roundToInt()) } },
        hours = hours.map { (it ?: 0.0).roundToInt() },
        weekdays = weekdays.map { (it ?: 0.0).roundToInt() },
        topArtists = topArtists.mapNotNull { a ->
            a?.name?.let { WrappedArtist(it, a.minutes.roundToInt(), a.plays, a.share, a.days, a.longestRunDays, a.topSong, a.image) }
        },
        topSongs = topSongs.mapNotNull { s ->
            s?.name?.let {
                WrappedSong(it, s.artist.orEmpty(), s.album, s.image, s.plays, s.minutes.roundToInt(), month(s.peakMonth), day(s.firstPlayed))
            }
        },
        topAlbums = topAlbums.mapNotNull { a ->
            a?.name?.let { WrappedAlbum(it, a.artist.orEmpty(), a.image, a.plays, a.minutes.roundToInt(), a.songs) }
        },
        months = months.mapNotNull { m ->
            m?.let { month(it.month) }?.let { WrappedMonth(it, m.minutes.roundToInt(), m.topArtist, m.topSong, m.image) }
        },
        discoveries = (discoveries ?: DiscoveriesDto()).let { d ->
            Discoveries(d.artists, d.songs, d.share, d.top.mapNotNull { t -> t?.name?.let { Discovery(it, t.minutes.roundToInt(), t.image) } })
        },
        listeningAge = listeningAge?.let { l ->
            val age = l.age ?: return@let null
            val window = l.window.filterNotNull().takeIf { it.size == 2 }?.let { it[0]..it[1] }
            ListeningAge(age, l.medianYear, window, l.coverage, l.decades.mapNotNull { d -> d?.decade?.let { DecadeShare(it, d.share) } })
        },
        streak = streak?.let { s ->
            val from = day(s.from) ?: return@let null
            DayRun(s.days, from, day(s.to) ?: from)
        },
    )
}

// --- parsing helpers ---

private fun day(s: String?): LocalDate? = s?.let { parse { LocalDate.parse(it) } }

private fun month(s: String?): YearMonth? = s?.let { parse { YearMonth.parse(it.take(7)) } }

private fun instant(s: String?): Instant? = s?.let { parse { Instant.parse(it) } }

private inline fun <T> parse(block: () -> T): T? = try { block() } catch (_: DateTimeParseException) { null }

private fun malformed(what: String) =
    WarehouseException(WarehouseException.Problem.BadResponse, "The warehouse sent an incomplete $what")
