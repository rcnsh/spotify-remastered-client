package app.remastered.core

import app.remastered.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's Liked Songs, kept in step with likes made in the app. Powers the ♥ marks in lists
 * and the artist page's "In your library", which replaces Popular now that Spotify removed top
 * tracks.
 *
 * The list is saved in [store], so a start shows it at once and only asks Spotify for what's
 * new: newest first, page by page, until it reaches a song it has. That has to line up with
 * what was saved and add up to Spotify's total, or (a song unliked elsewhere, say) it loads
 * everything again, 50 per request. Loading everything on every start got the app rate limited
 * for hours.
 */
class LikedIndex(
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val store: Store = Store.None,
    private val maxTracks: Int = 5_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Where the list is kept between runs. [load] is null when nothing was saved. */
    interface Store {
        fun load(): List<Track>?
        fun save(tracks: List<Track>)
        fun clear()

        object None : Store {
            override fun load(): List<Track>? = null
            override fun save(tracks: List<Track>) = Unit
            override fun clear() = Unit
        }
    }

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _uris = MutableStateFlow<Set<String>>(emptySet())
    val uris: StateFlow<Set<String>> = _uris.asStateFlow()
    private val _complete = MutableStateFlow(false)
    /** True once the list is known to match Spotify; a saved list shows before that. */
    val complete: StateFlow<Boolean> = _complete.asStateFlow()
    private var job: Job? = null
    private var restored = false

    /** Starts loading unless already loading or loaded. */
    fun load() = start(fromSaved = true)

    /** Drops what's loaded (and saved) and loads everything again. */
    fun reload() {
        job?.cancel()
        _complete.value = false
        publish(emptyList())
        start(fromSaved = false)
    }

    /** Forgets the list, saved copy included (signing out). */
    fun clear() {
        job?.cancel()
        _complete.value = false
        restored = true
        publish(emptyList())
        scope.launch { withContext(io) { runCatching { store.clear() } } }
    }

    private fun start(fromSaved: Boolean) {
        if (job?.isActive == true || _complete.value) return
        job = scope.launch {
            if (fromSaved && !restored) {
                restored = true
                withContext(io) { runCatching { store.load() }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let(::publish)
            }
            val known = _tracks.value
            try {
                val all = (if (fromSaved && known.isNotEmpty()) catchUp(known) else null) ?: loadAll(progressive = known.isEmpty())
                publish(all)
                _complete.value = true
                withContext(io) { runCatching { store.save(all) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or rate limited: keep what's shown; the next load() tries again.
            }
        }
    }

    /**
     * [known] with the songs liked since prepended, or null when that can't be trusted: what
     * Spotify lists after the new songs doesn't match [known]'s start (songs unliked or
     * re-liked), or the count doesn't match Spotify's total.
     */
    private suspend fun catchUp(known: List<Track>): List<Track>? {
        val have = known.mapTo(HashSet()) { it.uri }
        val fresh = mutableListOf<Track>()
        var offset = 0
        repeat(CATCH_UP_PAGES) {
            val page = catalog.likedTracks(offset, PAGE)
            val newer = page.items.takeWhile { it.uri !in have }
            fresh += newer
            if (newer.size < page.items.size || !page.hasMore || page.items.isEmpty()) {
                val rest = page.items.drop(newer.size).map { it.uri }
                if (rest != known.take(rest.size).map { it.uri }) return null
                val all = (fresh + known).take(maxTracks)
                val total = page.total ?: return all
                return all.takeIf { it.size == minOf(total, maxTracks) }
            }
            offset += page.items.size
        }
        // More new songs than a catch-up reads: load the lot.
        return null
    }

    /** Every liked song, 50 per request; shown as it arrives when [progressive]. */
    private suspend fun loadAll(progressive: Boolean): List<Track> {
        val all = mutableListOf<Track>()
        var offset = 0
        while (all.size < maxTracks) {
            val page = catalog.likedTracks(offset, PAGE)
            all += page.items
            if (progressive) publish(all.toList())
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        return all.take(maxTracks)
    }

    private fun publish(tracks: List<Track>) {
        _tracks.value = tracks
        _uris.value = tracks.mapTo(HashSet()) { it.uri }
    }

    /** Records a like/unlike made in the app, so lists update without a reload. */
    fun set(track: Track?, uri: String, liked: Boolean) {
        _uris.update { if (liked) it + uri else it - uri }
        _tracks.update { list ->
            if (liked) { if (track != null && list.none { it.uri == uri }) listOf(track) + list else list }
            else list.filterNot { it.uri == uri }
        }
        if (_complete.value) {
            val all = _tracks.value
            scope.launch { withContext(io) { runCatching { store.save(all) } } }
        }
    }

    fun byArtist(artistId: String) = _tracks.value.filter { t -> t.artists.any { it.id == artistId } }

    private companion object {
        const val PAGE = 50
        /** Pages of new songs a catch-up reads before giving up and loading everything. */
        const val CATCH_UP_PAGES = 4
    }
}
