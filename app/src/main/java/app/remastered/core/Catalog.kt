package app.remastered.core

import app.remastered.core.model.Album
import app.remastered.core.model.AlbumDetail
import app.remastered.core.model.AlbumGroup
import app.remastered.core.model.Artist
import app.remastered.core.model.Device
import app.remastered.core.model.Page
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.Playlist
import app.remastered.core.model.PlaylistItem
import app.remastered.core.model.Queue
import app.remastered.core.model.SearchResults
import app.remastered.core.model.SearchType
import app.remastered.core.model.TimeRange
import app.remastered.core.model.Track
import app.remastered.core.model.UserRef

/**
 * Everything the app reads or changes through Spotify's Web API. Implemented by
 * [app.remastered.spotify.web.WebCatalog].
 *
 * Every method may throw [CatalogException]. Only endpoints available to a Development Mode
 * app after Spotify's Feb 2026 changes are here (see docs/decisions/0004).
 */
interface Catalog {
    suspend fun me(): UserRef

    // Home
    suspend fun recentlyPlayed(limit: Int = 50): List<PlayHistory>
    suspend fun topArtists(range: TimeRange, limit: Int = 20): List<Artist>
    suspend fun topTracks(range: TimeRange, limit: Int = 20): List<Track>

    // Library
    suspend fun myPlaylists(offset: Int = 0, limit: Int = 50): Page<Playlist>
    suspend fun savedAlbums(offset: Int = 0, limit: Int = 50): Page<Album>
    suspend fun followedArtists(limit: Int = 50): List<Artist>
    suspend fun likedTracks(offset: Int = 0, limit: Int = 50): Page<Track>

    // Pages
    suspend fun playlist(id: String): Playlist
    /** Only for playlists the user owns or collaborates on; see [app.remastered.core.model.canReadItems]. */
    suspend fun playlistItems(id: String, offset: Int = 0, limit: Int = 50): Page<PlaylistItem>
    suspend fun album(id: String): AlbumDetail
    suspend fun albumTracks(id: String, offset: Int = 0, limit: Int = 50): Page<Track>
    suspend fun artist(id: String): Artist
    suspend fun artistAlbums(id: String, groups: Set<AlbumGroup>, offset: Int = 0, limit: Int = 10): Page<Album>

    // Search: Spotify caps Development Mode at 10 results per request.
    suspend fun search(query: String, types: Set<SearchType>, offset: Int = 0, limit: Int = 10): SearchResults

    // Player-adjacent (Premium)
    suspend fun queue(): Queue
    suspend fun devices(): List<Device>
    suspend fun transferPlayback(deviceId: String, play: Boolean = true)
    suspend fun setVolume(percent: Int, deviceId: String? = null)

    // Library writes (PUT/DELETE /me/library, by uri)
    suspend fun libraryContains(uris: List<String>): List<Boolean>
    suspend fun saveToLibrary(uris: List<String>)
    suspend fun removeFromLibrary(uris: List<String>)

    // Playlist writes (own playlists only)
    suspend fun createPlaylist(name: String, description: String = ""): Playlist
    suspend fun addToPlaylist(playlistId: String, uris: List<String>, position: Int? = null): String
    suspend fun removeFromPlaylist(playlistId: String, uris: List<String>, snapshotId: String?): String
    suspend fun reorderPlaylist(playlistId: String, rangeStart: Int, insertBefore: Int, snapshotId: String?): String
}

/** A Web API failure the UI can show. [status] is the HTTP status, or null for network errors. */
class CatalogException(
    message: String,
    val status: Int? = null,
    val reason: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isAuth get() = status == 401
    val isForbidden get() = status == 403
    val isRateLimited get() = status == 429
}
