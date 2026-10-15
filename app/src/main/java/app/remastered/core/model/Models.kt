package app.remastered.core.model

/**
 * The app's own view of Spotify's catalogue. Web API and App Remote types are mapped onto
 * these at the edge (spotify.web, spotify.remote), so screens never see either SDK.
 *
 * Ids are Spotify base-62 ids; uris are `spotify:<type>:<id>`.
 */

/** An image. [url] is https (Web API) or `appremote:<id>` (App Remote). */
data class Image(val url: String, val width: Int? = null, val height: Int? = null)

/** Picks the smallest image at least [minPx] wide, else the largest. */
fun List<Image>.best(minPx: Int = 300): Image? =
    filter { (it.width ?: Int.MAX_VALUE) >= minPx }.minByOrNull { it.width ?: Int.MAX_VALUE }
        ?: maxByOrNull { it.width ?: 0 }

data class UserRef(val id: String, val displayName: String, val images: List<Image> = emptyList())

data class ArtistRef(val id: String, val name: String) {
    val uri get() = "spotify:artist:$id"
}

data class AlbumRef(val id: String, val name: String, val images: List<Image> = emptyList()) {
    val uri get() = "spotify:album:$id"
}

data class Track(
    val id: String,
    val name: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val durationMs: Long,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isPlayable: Boolean = true,
    val isEpisode: Boolean = false,
) {
    val uri get() = if (isEpisode) "spotify:episode:$id" else "spotify:track:$id"
    val artistNames get() = artists.joinToString(", ") { it.name }
}

enum class AlbumType { Album, Single, Compilation }

data class Album(
    val id: String,
    val name: String,
    val artists: List<ArtistRef>,
    val images: List<Image>,
    val type: AlbumType,
    /** "2013", "2013-10" or "2013-10-02", as Spotify reports it. */
    val releaseDate: String,
    val totalTracks: Int,
) {
    val uri get() = "spotify:album:$id"
    val year get() = releaseDate.take(4)
}

data class AlbumDetail(val album: Album, val tracks: Page<Track>)

data class Artist(
    val id: String,
    val name: String,
    val images: List<Image>,
    val genres: List<String> = emptyList(),
) {
    val uri get() = "spotify:artist:$id"
}

data class Playlist(
    val id: String,
    val name: String,
    val description: String,
    val images: List<Image>,
    val owner: UserRef,
    val trackCount: Int,
    val collaborative: Boolean,
    val snapshotId: String,
) {
    val uri get() = "spotify:playlist:$id"
}

/** Spotify only returns items of playlists the user owns or collaborates on (Feb 2026). */
fun Playlist.canReadItems(me: UserRef?) = collaborative || owner.id == me?.id
fun Playlist.canEdit(me: UserRef?) = canReadItems(me)

/**
 * [position] is the item's index in the playlist as Spotify counts it (local files included), which
 * playback and reordering need; -1 when unknown.
 */
data class PlaylistItem(val track: Track, val addedAtEpochMs: Long?, val position: Int = -1)

data class Page<T>(val items: List<T>, val offset: Int, val total: Int?, val hasMore: Boolean) {
    companion object {
        fun <T> of(items: List<T>) = Page(items, 0, items.size, false)
    }
}

/** A podcast. Spotify no longer sends the publisher (Feb 2026). */
data class Show(
    val id: String,
    val name: String,
    val description: String,
    val images: List<Image>,
    val totalEpisodes: Int,
) {
    val uri get() = "spotify:show:$id"
}

/** An episode of a show: [track] (with [Track.isEpisode]) is what plays and what menus take. */
data class Episode(val track: Track, val description: String, val releaseDate: String)

data class ContextRef(val uri: String, val type: String)

data class PlayHistory(val track: Track, val playedAtEpochMs: Long, val context: ContextRef?)

data class Queue(val current: Track?, val upNext: List<Track>)

enum class DeviceType { Smartphone, Computer, Speaker, TV, CastVideo, CastAudio, Tablet, Other }

data class Device(
    val id: String?,
    val name: String,
    val type: DeviceType,
    val isActive: Boolean,
    val volumePercent: Int?,
    val supportsVolume: Boolean,
)

enum class SearchType(val api: String) { Track("track"), Artist("artist"), Album("album"), Playlist("playlist") }

data class SearchResults(
    val tracks: Page<Track>? = null,
    val artists: Page<Artist>? = null,
    val albums: Page<Album>? = null,
    val playlists: Page<Playlist>? = null,
)

enum class TimeRange(val api: String) { Short("short_term"), Medium("medium_term"), Long("long_term") }

enum class AlbumGroup(val api: String) { Album("album"), Single("single"), Compilation("compilation"), AppearsOn("appears_on") }
