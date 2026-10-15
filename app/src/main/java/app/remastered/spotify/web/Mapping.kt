package app.remastered.spotify.web

import app.remastered.core.model.Album
import app.remastered.core.model.AlbumRef
import app.remastered.core.model.AlbumType
import app.remastered.core.model.Artist
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.ContextRef
import app.remastered.core.model.Device
import app.remastered.core.model.DeviceType
import app.remastered.core.model.Episode
import app.remastered.core.model.Image
import app.remastered.core.model.Page
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.Playlist
import app.remastered.core.model.PlaylistItem
import app.remastered.core.model.Show
import app.remastered.core.model.Track
import app.remastered.core.model.UserRef
import java.time.Instant
import java.time.format.DateTimeParseException

// DTO -> model. Mappers return null for entries the app can't use (no id, local files, unknown
// types) so callers can mapNotNull.

internal fun List<ImageDto?>?.toImages(): List<Image> =
    orEmpty().mapNotNull { dto -> dto?.url?.let { Image(it, dto.width, dto.height) } }

internal fun UserDto.toModel() = UserRef(id, displayName ?: id, images.toImages())

internal fun ArtistDto.toRef(): ArtistRef? = id?.let { ArtistRef(it, name) }

internal fun ArtistDto.toModel(): Artist? = id?.let { Artist(it, name, images.toImages(), genres) }

internal fun AlbumDto.toRef(): AlbumRef? = id?.let { AlbumRef(it, name, images.toImages()) }

internal fun AlbumDto.toModel(): Album? = id?.let {
    Album(
        id = it,
        name = name,
        artists = artists.mapNotNull { a -> a?.toRef() },
        images = images.toImages(),
        type = when (albumType?.lowercase()) {
            "single" -> AlbumType.Single
            "compilation" -> AlbumType.Compilation
            else -> AlbumType.Album
        },
        releaseDate = releaseDate,
        totalTracks = totalTracks,
    )
}

/**
 * [fallbackAlbum] fills in the album for simplified tracks (album tracks), which omit it.
 * Episodes map with their show standing in for the album and no artists.
 */
internal fun TrackDto.toModel(fallbackAlbum: AlbumRef? = null): Track? {
    val id = id ?: return null
    if (isLocal) return null
    return when (type) {
        "track" -> Track(
            id = id,
            name = name,
            artists = artists.mapNotNull { it?.toRef() },
            album = album?.toRef() ?: fallbackAlbum,
            durationMs = durationMs,
            explicit = explicit,
            trackNumber = trackNumber,
            discNumber = discNumber,
            isPlayable = isPlayable,
        )
        "episode" -> Track(
            id = id,
            name = name,
            artists = emptyList(),
            album = show?.id?.let { AlbumRef(it, show.name, show.images.toImages().ifEmpty { images.toImages() }) } ?: fallbackAlbum,
            durationMs = durationMs,
            explicit = explicit,
            isPlayable = isPlayable,
            isEpisode = true,
        )
        else -> null
    }
}

/** An episode of [show] (`/shows/{id}/episodes` leaves the show out of each episode). */
internal fun TrackDto.toEpisode(show: AlbumRef?): Episode? {
    if (type != "episode") return null
    val track = toModel(show) ?: return null
    return Episode(track, description.orEmpty(), releaseDate.orEmpty())
}

internal fun ShowDto.toModel(): Show? = id?.let { Show(it, name, description.orEmpty().trim(), images.toImages(), totalEpisodes) }

internal fun PlaylistDto.toModel(): Playlist? = id?.let {
    Playlist(
        id = it,
        name = name,
        description = description.orEmpty().htmlText(),
        images = images.toImages(),
        owner = owner?.toModel() ?: UserRef("", ""),
        trackCount = (items ?: tracks)?.total ?: 0,
        collaborative = collaborative,
        snapshotId = snapshotId,
    )
}

internal fun PlaylistEntryDto.toModel(position: Int = -1): PlaylistItem? {
    if (isLocal) return null
    val track = content?.toModel() ?: return null
    return PlaylistItem(track, addedAt.toEpochMs(), position)
}

private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);")

/**
 * Playlist descriptions come HTML-escaped (`It&#x27;s`). Decodes the entities Spotify uses so the
 * text shows, and edits round-trip, as the user typed it.
 */
internal fun String.htmlText(): String = entity.replace(this) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> (if (e[1] == 'x' || e[1] == 'X') e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())
            ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
    }
}

internal fun PlayHistoryDto.toModel(): PlayHistory? {
    val track = track?.toModel() ?: return null
    val played = playedAt.toEpochMs() ?: return null
    val ctx = context?.uri?.let { ContextRef(it, context.type.orEmpty()) }
    return PlayHistory(track, played, ctx)
}

internal fun DeviceDto.toModel() = Device(
    id = id,
    name = name,
    type = when (type.lowercase()) {
        "smartphone" -> DeviceType.Smartphone
        "computer" -> DeviceType.Computer
        "speaker", "avr", "audiodongle" -> DeviceType.Speaker
        "tv", "stb" -> DeviceType.TV
        "castvideo" -> DeviceType.CastVideo
        "castaudio" -> DeviceType.CastAudio
        "tablet" -> DeviceType.Tablet
        else -> DeviceType.Other
    },
    isActive = isActive,
    volumePercent = volumePercent,
    supportsVolume = supportsVolume ?: (volumePercent != null),
)

internal fun <D : Any, M> PagingDto<D>.toPage(map: (D) -> M?): Page<M> =
    Page(items.mapNotNull { it?.let(map) }, offset, total, next != null)

internal fun String?.toEpochMs(): Long? = this?.let {
    try {
        Instant.parse(it).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
