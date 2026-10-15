package app.remastered.spotify.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes of the Web API. Only fields the app maps are declared; anything Spotify removed in
// Feb 2026 (popularity, followers, available_markets, label, email, country, product) is absent on
// purpose. Lists default to empty and may hold nulls where Spotify is known to send them.

@Serializable
internal data class ImageDto(val url: String? = null, val width: Int? = null, val height: Int? = null)

@Serializable
internal data class UserDto(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val images: List<ImageDto?> = emptyList(),
)

@Serializable
internal data class ArtistDto(
    val id: String? = null,
    val name: String = "",
    val images: List<ImageDto?> = emptyList(),
    val genres: List<String> = emptyList(),
)

@Serializable
internal data class AlbumDto(
    val id: String? = null,
    val name: String = "",
    @SerialName("album_type") val albumType: String? = null,
    val artists: List<ArtistDto?> = emptyList(),
    val images: List<ImageDto?> = emptyList(),
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("total_tracks") val totalTracks: Int = 0,
    val tracks: PagingDto<TrackDto>? = null,
)

@Serializable
internal data class ShowDto(val id: String? = null, val name: String = "", val images: List<ImageDto?> = emptyList())

/** A track or, with `type == "episode"`, an episode: both share this shape closely enough. */
@Serializable
internal data class TrackDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "track",
    val artists: List<ArtistDto?> = emptyList(),
    val album: AlbumDto? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val explicit: Boolean = false,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    @SerialName("is_playable") val isPlayable: Boolean = true,
    @SerialName("is_local") val isLocal: Boolean = false,
    // Episode-only.
    val images: List<ImageDto?> = emptyList(),
    val show: ShowDto? = null,
)

@Serializable
internal data class PagingDto<T>(
    val items: List<T?> = emptyList(),
    val offset: Int = 0,
    val total: Int? = null,
    val next: String? = null,
)

@Serializable
internal data class CursorsDto(val after: String? = null)

@Serializable
internal data class CursorPagingDto<T>(
    val items: List<T?> = emptyList(),
    val next: String? = null,
    val cursors: CursorsDto? = null,
    val total: Int? = null,
)

@Serializable
internal data class FollowedArtistsDto(val artists: CursorPagingDto<ArtistDto>? = null)

/**
 * A playlist entry. Feb 2026 renamed `track` to `item`; the old name is still sent as a deprecated
 * duplicate, so read whichever is present.
 */
@Serializable
internal data class PlaylistEntryDto(
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
    val item: TrackDto? = null,
    val track: TrackDto? = null,
) {
    val content get() = item ?: track
}

/**
 * Feb 2026 renamed the playlist's `tracks` page to `items` (absent entirely for playlists the
 * user neither owns nor collaborates on). In `/me/playlists` and search it's just `{href,total}`.
 */
@Serializable
internal data class PlaylistDto(
    val id: String? = null,
    val name: String = "",
    val description: String? = null,
    val images: List<ImageDto?>? = null,
    val owner: UserDto? = null,
    val collaborative: Boolean = false,
    @SerialName("snapshot_id") val snapshotId: String = "",
    val items: PagingDto<PlaylistEntryDto>? = null,
    val tracks: PagingDto<PlaylistEntryDto>? = null,
)

@Serializable
internal data class SavedTrackDto(@SerialName("added_at") val addedAt: String? = null, val track: TrackDto? = null)

@Serializable
internal data class SavedAlbumDto(@SerialName("added_at") val addedAt: String? = null, val album: AlbumDto? = null)

@Serializable
internal data class ContextDto(val uri: String? = null, val type: String? = null)

@Serializable
internal data class PlayHistoryDto(
    val track: TrackDto? = null,
    @SerialName("played_at") val playedAt: String? = null,
    val context: ContextDto? = null,
)

@Serializable
internal data class RecentlyPlayedDto(val items: List<PlayHistoryDto?> = emptyList())

@Serializable
internal data class QueueDto(
    @SerialName("currently_playing") val currentlyPlaying: TrackDto? = null,
    val queue: List<TrackDto?> = emptyList(),
)

@Serializable
internal data class DeviceDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("volume_percent") val volumePercent: Int? = null,
    @SerialName("supports_volume") val supportsVolume: Boolean? = null,
)

@Serializable
internal data class DevicesDto(val devices: List<DeviceDto?> = emptyList())

@Serializable
internal data class SearchDto(
    val tracks: PagingDto<TrackDto>? = null,
    val artists: PagingDto<ArtistDto>? = null,
    val albums: PagingDto<AlbumDto>? = null,
    // Spotify is known to return null entries here.
    val playlists: PagingDto<PlaylistDto>? = null,
)

@Serializable
internal data class SnapshotDto(@SerialName("snapshot_id") val snapshotId: String = "")
