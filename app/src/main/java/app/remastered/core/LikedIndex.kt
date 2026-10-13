package app.remastered.core

import app.remastered.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The user's Liked Songs, loaded once in the background (50 per request) and kept in step
 * with likes made in the app. Powers the ♥ marks in lists and the artist page's
 * "In your library", which replaces Popular now that Spotify removed top tracks.
 */
class LikedIndex(private val catalog: Catalog, private val scope: CoroutineScope, private val maxTracks: Int = 5_000) {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _uris = MutableStateFlow<Set<String>>(emptySet())
    val uris: StateFlow<Set<String>> = _uris.asStateFlow()
    private val _complete = MutableStateFlow(false)
    val complete: StateFlow<Boolean> = _complete.asStateFlow()
    private var job: Job? = null

    /** Starts loading unless already loading or loaded. */
    fun load() {
        if (job?.isActive == true || _complete.value) return
        job = scope.launch {
            val all = mutableListOf<Track>()
            var offset = 0
            runCatching {
                while (all.size < maxTracks) {
                    val page = catalog.likedTracks(offset, 50)
                    all += page.items
                    _tracks.value = all.toList()
                    _uris.value = all.mapTo(HashSet()) { it.uri }
                    if (!page.hasMore || page.items.isEmpty()) break
                    offset += page.items.size
                }
                _complete.value = true
            }
        }
    }

    /** Drops what's loaded and loads again from the start. */
    fun reload() {
        job?.cancel()
        _complete.value = false
        _tracks.value = emptyList()
        _uris.value = emptySet()
        load()
    }

    /** Records a like/unlike made in the app, so lists update without a reload. */
    fun set(track: Track?, uri: String, liked: Boolean) {
        _uris.update { if (liked) it + uri else it - uri }
        _tracks.update { list ->
            if (liked) { if (track != null && list.none { it.uri == uri }) listOf(track) + list else list }
            else list.filterNot { it.uri == uri }
        }
    }

    fun byArtist(artistId: String) = _tracks.value.filter { t -> t.artists.any { it.id == artistId } }
}
