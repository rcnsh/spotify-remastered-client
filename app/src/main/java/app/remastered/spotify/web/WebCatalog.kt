package app.remastered.spotify.web

import app.remastered.core.Catalog
import app.remastered.core.CatalogException
import app.remastered.core.model.Album
import app.remastered.core.model.AlbumDetail
import app.remastered.core.model.AlbumGroup
import app.remastered.core.model.AlbumRef
import app.remastered.core.model.Artist
import app.remastered.core.model.Device
import app.remastered.core.model.Episode
import app.remastered.core.model.Page
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.Playlist
import app.remastered.core.model.PlaylistItem
import app.remastered.core.model.Queue
import app.remastered.core.model.SearchResults
import app.remastered.core.model.SearchType
import app.remastered.core.model.Show
import app.remastered.core.model.TimeRange
import app.remastered.core.model.Track
import app.remastered.core.model.UserRef
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [Catalog] over the Spotify Web API, restricted to what a Development Mode app may call after
 * the Feb 2026 changes (no batch, browse, recommendations, top-tracks, other users' data).
 *
 * Every call runs on [io]. A 401 triggers one [TokenProvider.refresh] and retry; a 429 is retried
 * up to [MAX_RATE_RETRIES] times honouring `Retry-After`, unless the wait exceeds
 * [MAX_RETRY_AFTER_S]. A 429 that isn't retried goes into [limits], and that endpoint fails
 * without a request until the wait is over. [sleep] is injectable so tests don't wait.
 */
class WebCatalog(
    private val http: OkHttpClient,
    private val tokens: TokenProvider,
    baseUrl: String = "https://api.spotify.com/v1/",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val limits: RateLimits = RateLimits(),
) : Catalog {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    // Album tracks and show episodes come without their album or show; remember the ones we've
    // seen to fill AlbumRef.
    private val albumRefs = object : LinkedHashMap<String, AlbumRef>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumRef>?) = size > 200
    }

    override suspend fun me(): UserRef = get<UserDto>(url("me")).toModel()

    override suspend fun recentlyPlayed(limit: Int): List<PlayHistory> =
        get<RecentlyPlayedDto>(url("me/player/recently-played") { limit(limit, 50) })
            .items.mapNotNull { it?.toModel() }

    override suspend fun topArtists(range: TimeRange, limit: Int): List<Artist> =
        get<PagingDto<ArtistDto>>(url("me/top/artists") { add("time_range", range.api); limit(limit, 50) })
            .items.mapNotNull { it?.toModel() }

    override suspend fun topTracks(range: TimeRange, limit: Int): List<Track> =
        get<PagingDto<TrackDto>>(url("me/top/tracks") { add("time_range", range.api); limit(limit, 50) })
            .items.mapNotNull { it?.toModel() }

    override suspend fun myPlaylists(offset: Int, limit: Int): Page<Playlist> =
        get<PagingDto<PlaylistDto>>(url("me/playlists") { page(offset, limit, 50) }).toPage { it.toModel() }

    override suspend fun savedAlbums(offset: Int, limit: Int): Page<Album> =
        get<PagingDto<SavedAlbumDto>>(url("me/albums") { page(offset, limit, 50) }).toPage { it.album?.toModel() }

    override suspend fun followedArtists(limit: Int): List<Artist> {
        // Cursor-paged; follow `after` until we have enough or Spotify runs out.
        val out = mutableListOf<Artist>()
        var after: String? = null
        repeat(MAX_CURSOR_PAGES) {
            val want = (limit - out.size).coerceIn(1, 50)
            val page = get<FollowedArtistsDto>(url("me/following") {
                add("type", "artist")
                add("limit", want.toString())
                after?.let { add("after", it) }
            }).artists ?: return out
            out += page.items.mapNotNull { it?.toModel() }
            after = page.cursors?.after
            if (out.size >= limit || after == null || page.items.isEmpty()) return out.take(limit)
        }
        return out.take(limit)
    }

    override suspend fun likedTracks(offset: Int, limit: Int): Page<Track> =
        get<PagingDto<SavedTrackDto>>(url("me/tracks") { page(offset, limit, 50) }).toPage { it.track?.toModel() }

    override suspend fun savedShows(offset: Int, limit: Int): Page<Show> =
        get<PagingDto<SavedShowDto>>(url("me/shows") { page(offset, limit, 50) }).toPage { it.show?.toModel() }

    override suspend fun playlist(id: String): Playlist =
        get<PlaylistDto>(url("playlists", id)).toModel() ?: throw malformed("playlist")

    override suspend fun playlistItems(id: String, offset: Int, limit: Int): Page<PlaylistItem> {
        val dto = get<PagingDto<PlaylistEntryDto>>(url("playlists", id, "items") {
            page(offset, limit, 50)
            add("additional_types", "track,episode")
        })
        // Count every entry, local files included, so positions match Spotify's.
        val items = dto.items.mapIndexedNotNull { i, entry -> entry?.toModel(dto.offset + i) }
        return Page(items, dto.offset, dto.total, dto.next != null)
    }

    override suspend fun album(id: String): AlbumDetail {
        val dto = get<AlbumDto>(url("albums", id))
        val album = dto.toModel() ?: throw malformed("album")
        val ref = AlbumRef(album.id, album.name, album.images).also { remember(it) }
        val tracks = dto.tracks?.toPage { it.toModel(ref) } ?: Page(emptyList(), 0, 0, false)
        return AlbumDetail(album, tracks)
    }

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Track> {
        val ref = synchronized(albumRefs) { albumRefs[id] } ?: album(id).album.let { AlbumRef(it.id, it.name, it.images) }
        return get<PagingDto<TrackDto>>(url("albums", id, "tracks") { page(offset, limit, 50) }).toPage { it.toModel(ref) }
    }

    override suspend fun artist(id: String): Artist =
        get<ArtistDto>(url("artists", id)).toModel() ?: throw malformed("artist")

    // Capped at 10 per request (reference docs; not in the Feb 2026 guide): above that Spotify
    // rejects the call outright, so clamp rather than pass the caller's limit through.
    override suspend fun artistAlbums(id: String, groups: Set<AlbumGroup>, offset: Int, limit: Int): Page<Album> =
        get<PagingDto<AlbumDto>>(url("artists", id, "albums") {
            if (groups.isNotEmpty()) addEncoded("include_groups", groups.joinToString(",") { it.api })
            page(offset, limit, 10)
        }).toPage { it.toModel() }

    override suspend fun show(id: String): Show =
        (get<ShowDto>(url("shows", id)).toModel() ?: throw malformed("show")).also { remember(AlbumRef(it.id, it.name, it.images)) }

    // Episodes come without their show, which stands in for the album; as with album tracks.
    override suspend fun showEpisodes(id: String, offset: Int, limit: Int): Page<Episode> {
        val show = synchronized(albumRefs) { albumRefs[id] } ?: show(id).let { AlbumRef(it.id, it.name, it.images) }
        return get<PagingDto<TrackDto>>(url("shows", id, "episodes") { page(offset, limit, 50) }).toPage { it.toEpisode(show) }
    }

    override suspend fun search(query: String, types: Set<SearchType>, offset: Int, limit: Int): SearchResults {
        if (query.isBlank() || types.isEmpty()) return SearchResults()
        // Development Mode caps search at 10 per request (Feb 2026); paginate with offset.
        val dto = get<SearchDto>(url("search") {
            add("q", query)
            addEncoded("type", types.joinToString(",") { it.api })
            page(offset, limit, 10)
        })
        return SearchResults(
            tracks = dto.tracks?.toPage { it.toModel() },
            artists = dto.artists?.toPage { it.toModel() },
            albums = dto.albums?.toPage { it.toModel() },
            playlists = dto.playlists?.toPage { it.toModel() },
        )
    }

    override suspend fun queue(): Queue {
        val dto = get<QueueDto>(url("me/player/queue"))
        return Queue(dto.currentlyPlaying?.toModel(), dto.queue.mapNotNull { it?.toModel() })
    }

    override suspend fun devices(): List<Device> =
        get<DevicesDto>(url("me/player/devices")).devices.mapNotNull { it?.toModel() }

    override suspend fun transferPlayback(deviceId: String, play: Boolean) {
        send(url("me/player"), "PUT", jsonBody {
            putJsonArray("device_ids") { add(deviceId) }
            put("play", play)
        })
    }

    override suspend fun setVolume(percent: Int, deviceId: String?) {
        send(url("me/player/volume") {
            add("volume_percent", percent.coerceIn(0, 100).toString())
            deviceId?.let { add("device_id", it) }
        }, "PUT", EMPTY_BODY)
    }

    // /me/library takes a comma-separated `uris` query parameter, max 40 per request
    // (reference: save-library-items, remove-library-items, check-library-contains).
    override suspend fun libraryContains(uris: List<String>): List<Boolean> =
        uris.chunked(LIBRARY_CHUNK).flatMap { chunk ->
            get<List<Boolean>>(url("me/library/contains") { addEncoded("uris", csv(chunk)) })
        }

    override suspend fun saveToLibrary(uris: List<String>) {
        uris.chunked(LIBRARY_CHUNK).forEach { send(url("me/library") { addEncoded("uris", csv(it)) }, "PUT", EMPTY_BODY) }
    }

    override suspend fun removeFromLibrary(uris: List<String>) {
        uris.chunked(LIBRARY_CHUNK).forEach { send(url("me/library") { addEncoded("uris", csv(it)) }, "DELETE", null) }
    }

    override suspend fun createPlaylist(name: String, description: String): Playlist =
        decode<PlaylistDto>(send(url("me/playlists"), "POST", jsonBody {
            put("name", name)
            put("description", description)
            put("public", false)
        })).toModel() ?: throw malformed("playlist")

    override suspend fun addToPlaylist(playlistId: String, uris: List<String>, position: Int?): String {
        if (uris.isEmpty()) return playlist(playlistId).snapshotId
        var snapshot = ""
        uris.chunked(PLAYLIST_CHUNK).forEachIndexed { i, chunk ->
            snapshot = decode<SnapshotDto>(send(url("playlists", playlistId, "items"), "POST", jsonBody {
                putJsonArray("uris") { chunk.forEach { add(it) } }
                position?.let { put("position", it + i * PLAYLIST_CHUNK) }
            })).snapshotId
        }
        return snapshot
    }

    override suspend fun removeFromPlaylist(playlistId: String, uris: List<String>, snapshotId: String?): String {
        if (uris.isEmpty()) return snapshotId ?: playlist(playlistId).snapshotId
        var snapshot = snapshotId
        uris.chunked(PLAYLIST_CHUNK).forEach { chunk ->
            // Feb 2026: the body field is `items` (was `tracks`).
            snapshot = decode<SnapshotDto>(send(url("playlists", playlistId, "items"), "DELETE", jsonBody {
                putJsonArray("items") { chunk.forEach { add(buildJsonObject { put("uri", it) }) } }
                snapshot?.let { put("snapshot_id", it) }
            })).snapshotId
        }
        return snapshot.orEmpty()
    }

    override suspend fun reorderPlaylist(playlistId: String, rangeStart: Int, insertBefore: Int, snapshotId: String?): String =
        decode<SnapshotDto>(send(url("playlists", playlistId, "items"), "PUT", jsonBody {
            put("range_start", rangeStart)
            put("insert_before", insertBefore)
            put("range_length", 1)
            snapshotId?.let { put("snapshot_id", it) }
        })).snapshotId

    override suspend fun updatePlaylistDetails(playlistId: String, name: String, description: String) {
        send(url("playlists", playlistId), "PUT", jsonBody {
            put("name", name)
            put("description", description)
        })
    }

    // The body is the JPEG as base64 text, sent as image/jpeg (reference: upload-custom-playlist-cover).
    override suspend fun uploadPlaylistCover(playlistId: String, jpeg: ByteArray) {
        val body = Base64.getEncoder().encodeToString(jpeg)
        if (body.length > Catalog.MAX_COVER_BASE64) throw CatalogException("Cover image is too large", null, "COVER_TOO_LARGE")
        // As bytes: a String body would add "; charset=utf-8" to the content type.
        send(url("playlists", playlistId, "images"), "PUT", body.toByteArray(Charsets.US_ASCII).toRequestBody(JPEG_TYPE))
    }

    // --- plumbing ---

    private fun remember(ref: AlbumRef) = synchronized(albumRefs) { albumRefs[ref.id] = ref }

    private class Query(val b: HttpUrl.Builder) {
        fun add(name: String, value: String) { b.addQueryParameter(name, value) }
        /** For pre-encoded values: keeps commas literal, as Spotify's docs show. */
        fun addEncoded(name: String, value: String) { b.addEncodedQueryParameter(name, value) }
        fun limit(limit: Int, max: Int) = add("limit", limit.coerceIn(1, max).toString())
        fun page(offset: Int, limit: Int, max: Int) {
            limit(limit, max)
            add("offset", offset.coerceAtLeast(0).toString())
        }
    }

    private fun url(path: String, vararg segments: String, query: Query.() -> Unit = {}): HttpUrl {
        val b = base.newBuilder().addPathSegments(path)
        segments.forEach { b.addPathSegment(it) }
        Query(b).query()
        return b.build()
    }

    // The String charset name: URLEncoder.encode(String, Charset) is API 33, and minSdk is 29.
    private fun csv(values: List<String>) = values.joinToString(",") { URLEncoder.encode(it, "UTF-8") }

    private fun jsonBody(build: JsonObjectBuilder.() -> Unit): RequestBody =
        buildJsonObject(build).toString().toRequestBody(JSON_TYPE)

    private suspend inline fun <reified T> get(url: HttpUrl): T = decode(send(url, "GET", null))

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw CatalogException("Unexpected response from Spotify", null, "MALFORMED_RESPONSE", e)
    } catch (e: IllegalArgumentException) {
        throw CatalogException("Unexpected response from Spotify", null, "MALFORMED_RESPONSE", e)
    }

    private fun malformed(what: String) = CatalogException("Spotify returned an incomplete $what", null, "MALFORMED_RESPONSE")

    /** Sends with auth, 401-refresh and 429 handling; returns the body text of a 2xx response. */
    private suspend fun send(url: HttpUrl, method: String, body: RequestBody?): String =
        withContext(io) { sendOnIo(url, method, body) }

    private suspend fun sendOnIo(url: HttpUrl, method: String, body: RequestBody?): String {
        val endpoint = url.encodedPath
        limits.remainingMs(endpoint)?.let {
            throw CatalogException("Spotify 429: rate limited", 429, null, retryAfterMs = it)
        }
        var token = token { tokens.accessToken() }
        var refreshed = false
        var rateRetries = 0
        while (true) {
            val request = Request.Builder().url(url).method(method, body)
                .header("Authorization", "Bearer $token")
                .build()
            val reply = try {
                http.newCall(request).await().use { Reply(it.code, it.header("Retry-After"), it.body.string()) }
            } catch (e: IOException) {
                throw CatalogException("Network error talking to Spotify", null, null, e)
            }
            when {
                reply.code in 200..299 -> return reply.body
                reply.code == 401 && !refreshed -> {
                    refreshed = true
                    token = token { tokens.refresh() }
                }
                reply.code == 429 && rateRetries < MAX_RATE_RETRIES && retryAfterS(reply).let { it == null || it <= MAX_RETRY_AFTER_S } -> {
                    rateRetries++
                    sleep((retryAfterS(reply) ?: 1L) * 1000)
                }
                reply.code == 429 -> {
                    val waitS = retryAfterS(reply)
                    if (waitS != null) limits.limit(endpoint, waitS * 1000)
                    throw error(reply, waitS?.times(1000))
                }
                else -> throw error(reply)
            }
        }
    }

    private class Reply(val code: Int, val retryAfter: String?, val body: String)

    private fun retryAfterS(reply: Reply) = reply.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private suspend fun token(fetch: suspend () -> String): String = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogException) {
        throw e
    } catch (e: IOException) {
        throw CatalogException("Network error refreshing Spotify sign-in", null, null, e)
    } catch (e: Exception) {
        throw CatalogException("Not signed in to Spotify", 401, "NOT_SIGNED_IN", e)
    }

    /** Parses Spotify's `{"error":{"status","message","reason"}}`; tolerates anything else. */
    private fun error(reply: Reply, retryAfterMs: Long? = null): CatalogException {
        var message: String? = null
        var reason: String? = null
        try {
            when (val err = json.parseToJsonElement(reply.body).jsonObject["error"]) {
                is JsonObject -> {
                    message = err.str("message")
                    reason = err.str("reason")
                }
                is JsonPrimitive -> message = err.contentOrNull
                else -> Unit
            }
        } catch (_: Exception) {
            // Not JSON (e.g. a plain-text 429 or a proxy page).
        }
        return CatalogException("Spotify ${reply.code}: ${message ?: "request failed"}", reply.code, reason, retryAfterMs = retryAfterMs)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    internal companion object {
        const val MAX_RATE_RETRIES = 3
        const val MAX_RETRY_AFTER_S = 30L
        const val MAX_CURSOR_PAGES = 20
        const val LIBRARY_CHUNK = 40
        const val PLAYLIST_CHUNK = 100
        private val JSON_TYPE = "application/json".toMediaType()
        private val JPEG_TYPE = "image/jpeg".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
