package app.remastered.core

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.Image
import app.remastered.core.model.PlaylistItem
import app.remastered.core.model.Track
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the Spotify app lists under a uri (App Remote's ContentApi), read only. It lists the tracks
 * of any playlist, including ones the Web API won't (other people's, Spotify's own); see ADR 0004.
 * Implemented by [app.remastered.spotify.remote.AppRemotePlayer].
 */
interface RemoteContent {
    /** One page of [uri]'s children; null when App Remote isn't connected. Fails with Spotify's error. */
    suspend fun children(uri: String, limit: Int, offset: Int): ContentPage?
}

/** A page of children; [total] is Spotify's count for the whole list. */
data class ContentPage(val total: Int, val items: List<ContentItem>)

/** [imageUrl] is an App Remote image id (`appremote:` + raw), [subtitle] the artists as one string. */
data class ContentItem(val uri: String?, val title: String, val subtitle: String, val imageUrl: String?, val playable: Boolean)

object RemoteTracks {
    /**
     * Every track of playlist [uri] as App Remote lists them, at most [max], with their positions
     * in the playlist (for playback). Null when App Remote can't list them, so callers fall back.
     */
    suspend fun load(content: RemoteContent, uri: String, pageSize: Int = 50, max: Int = 2_000): List<PlaylistItem>? {
        val items = mutableListOf<PlaylistItem>()
        var offset = 0
        try {
            while (offset < max) {
                val page = content.children(uri, pageSize, offset) ?: return null
                page.items.forEachIndexed { i, item -> toItem(item, offset + i)?.let(items::add) }
                offset += page.items.size
                if (page.items.isEmpty() || offset >= page.total) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return items
    }

    /**
     * A track or episode as a playlist row, or null for anything else (local files, folders). Artist
     * and album ids aren't known here: the artists are one name, and the album only carries artwork.
     */
    fun toItem(item: ContentItem, position: Int): PlaylistItem? {
        val parts = item.uri?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != "spotify" || parts[1] !in setOf("track", "episode") || parts[2].isEmpty()) return null
        val track = Track(
            id = parts[2],
            name = item.title,
            artists = listOf(ArtistRef("", item.subtitle)).filter { it.name.isNotBlank() },
            album = item.imageUrl?.let { AlbumRef("", "", listOf(Image(it))) },
            durationMs = 0,
            isPlayable = item.playable,
            isEpisode = parts[1] == "episode",
        )
        return PlaylistItem(track, addedAtEpochMs = null, position = position)
    }
}
