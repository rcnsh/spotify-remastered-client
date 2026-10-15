package app.remastered.spotify.web

import app.remastered.core.CatalogException
import app.remastered.core.model.AlbumGroup
import app.remastered.core.model.AlbumType
import app.remastered.core.model.DeviceType
import app.remastered.core.model.SearchType
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WebCatalogTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private val sleeps = mutableListOf<Long>()
    private var now = 1_000_000L
    private val limitStore = RateLimits.InMemory()
    private lateinit var catalog: WebCatalog

    @Before fun setUp() {
        server.start()
        catalog = WebCatalog(OkHttpClient(), tokens, server.url("/v1/").toString(), sleep = { sleeps += it }, limits = RateLimits(limitStore) { now })
    }

    @After fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    private fun take(): RecordedRequest = server.takeRequest()
    private val RecordedRequest.path get() = url.encodedPath
    private fun RecordedRequest.query(name: String) = url.queryParameter(name)
    private val RecordedRequest.bodyText get() = body?.utf8().orEmpty()

    @Test fun `sends bearer token`() = runTest {
        enqueue("""{"id":"u1","display_name":"Ada","images":[{"url":"https://i/1","width":64,"height":64}]}""")
        val me = catalog.me()
        assertEquals("u1", me.id)
        assertEquals("Ada", me.displayName)
        assertEquals(64, me.images.single().width)
        val req = take()
        assertEquals("/v1/me", req.path)
        assertEquals("Bearer access-1", req.headers["Authorization"])
    }

    @Test fun `401 refreshes once and retries`() = runTest {
        enqueue("""{"error":{"status":401,"message":"The access token expired"}}""", 401)
        enqueue("""{"id":"u1"}""")
        assertEquals("u1", catalog.me().id)
        assertEquals(1, tokens.refreshes)
        assertEquals("Bearer access-1", take().headers["Authorization"])
        assertEquals("Bearer access-2", take().headers["Authorization"])
    }

    @Test fun `second 401 throws`() = runTest {
        enqueue("""{"error":{"status":401,"message":"Invalid access token"}}""", 401)
        enqueue("""{"error":{"status":401,"message":"Invalid access token"}}""", 401)
        val e = assertThrows { catalog.me() }
        assertEquals(401, e.status)
        assertTrue(e.isAuth)
        assertEquals(1, tokens.refreshes)
    }

    @Test fun `429 honours Retry-After then succeeds`() = runTest {
        enqueue("", 429, mapOf("Retry-After" to "2"))
        enqueue("", 429, mapOf("Retry-After" to "3"))
        enqueue("""{"id":"u1"}""")
        assertEquals("u1", catalog.me().id)
        assertEquals(listOf(2000L, 3000L), sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test fun `429 after retries throws with reason`() = runTest {
        val quota = """{"error":{"status":429,"message":"Quota exceeded","reason":"QUOTA_EXCEEDED"}}"""
        repeat(4) { enqueue(quota, 429, mapOf("Retry-After" to "1")) }
        val e = assertThrows { catalog.me() }
        assertEquals(429, e.status)
        assertEquals("QUOTA_EXCEEDED", e.reason)
        assertTrue(e.isRateLimited)
        assertEquals(4, server.requestCount)
        assertEquals(3, sleeps.size)
    }

    @Test fun `429 with long Retry-After throws immediately`() = runTest {
        enqueue("""{"error":{"status":429,"message":"slow down"}}""", 429, mapOf("Retry-After" to "3600"))
        val e = assertThrows { catalog.me() }
        assertEquals(429, e.status)
        assertTrue(sleeps.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `429 with long Retry-After stops requests to that endpoint until it passes`() = runTest {
        enqueue("", 429, mapOf("Retry-After" to "30630"))
        assertEquals(30_630_000L, assertThrows { catalog.likedTracks() }.retryAfterMs)

        now += 630_000
        val e = assertThrows { catalog.likedTracks(50) }
        assertTrue(e.isRateLimited)
        assertEquals(30_000_000L, e.retryAfterMs)
        assertEquals(1, server.requestCount)
        assertEquals(1_000_000L + 30_630_000L, limitStore.load()["/v1/me/tracks"])

        // Other endpoints are unaffected.
        enqueue("""{"id":"u1"}""")
        assertEquals("u1", catalog.me().id)

        now += 30_000_000
        enqueue("""{"items":[],"total":0}""")
        assertEquals(0, catalog.likedTracks().items.size)
        assertEquals(3, server.requestCount)
    }

    @Test fun `a limit from an earlier run is honoured`() = runTest {
        limitStore.save(mapOf("/v1/me/tracks" to now + 5_000))
        catalog = WebCatalog(OkHttpClient(), tokens, server.url("/v1/").toString(), sleep = { sleeps += it }, limits = RateLimits(limitStore) { now })
        assertEquals(5_000L, assertThrows { catalog.likedTracks() }.retryAfterMs)
        assertEquals(0, server.requestCount)
    }

    @Test fun `network failure has null status`() = runTest {
        server.close()
        val e = assertThrows { catalog.me() }
        assertNull(e.status)
    }

    @Test fun `search clamps limit to 10 and sends types`() = runTest {
        enqueue(
            """{"tracks":{"items":[$TRACK],"offset":10,"total":100,"next":"x"},
               "playlists":{"items":[null,$SIMPLE_PLAYLIST],"offset":10,"total":3,"next":null}}""",
        )
        val res = catalog.search("daft punk", linkedSetOf(SearchType.Track, SearchType.Playlist), offset = 10, limit = 50)
        val req = take()
        assertEquals("/v1/search", req.path)
        assertEquals("10", req.query("limit"))
        assertEquals("10", req.query("offset"))
        assertEquals("track,playlist", req.query("type"))
        assertEquals("daft punk", req.query("q"))
        assertNull(req.query("market"))
        assertEquals("t1", res.tracks!!.items.single().id)
        assertTrue(res.tracks.hasMore)
        assertEquals(1, res.playlists!!.items.size)
        assertEquals(42, res.playlists.items.single().trackCount)
        assertNull(res.artists)
    }

    @Test fun `artist albums clamps limit to 10 and sends groups`() = runTest {
        enqueue("""{"items":[{"id":"a1","name":"Discovery","album_type":"album","release_date":"2001-03-12"}],"offset":0,"total":12,"next":"n"}""")
        val page = catalog.artistAlbums("ar1", linkedSetOf(AlbumGroup.Album, AlbumGroup.Single), offset = 0, limit = 20)
        val req = take()
        assertEquals("/v1/artists/ar1/albums", req.path)
        assertEquals("10", req.query("limit"))
        assertEquals("album,single", req.query("include_groups"))
        assertEquals("a1", page.items.single().id)
        assertTrue(page.hasMore)
    }

    @Test fun `playlist items new shape`() = runTest {
        enqueue(
            """{"href":"h","limit":50,"offset":0,"total":5,"next":null,"items":[
                {"added_at":"2024-01-02T03:04:05Z","is_local":false,"item":$TRACK},
                {"added_at":"2024-01-02T03:04:05Z","is_local":false,"item":null},
                {"added_at":"2024-01-02T03:04:05Z","is_local":true,"item":{"id":null,"name":"local.mp3","type":"track","is_local":true}},
                {"added_at":null,"is_local":false,"item":$EPISODE},
                {"added_at":"2024-01-02T03:04:05Z","is_local":false,"item":{"id":"x","type":"audiobook_chapter"}}
            ]}""",
        )
        val page = catalog.playlistItems("p1")
        val req = take()
        assertEquals("/v1/playlists/p1/items", req.path)
        assertEquals("track,episode", req.query("additional_types"))
        assertEquals(listOf("t1", "e1"), page.items.map { it.track.id })
        assertEquals(1704164645000L, page.items[0].addedAtEpochMs)
        assertEquals("a1", page.items[0].track.album!!.id)
        val ep = page.items[1].track
        assertTrue(ep.isEpisode)
        assertEquals("spotify:episode:e1", ep.uri)
        assertEquals("Show", ep.album!!.name)
        assertNull(page.items[1].addedAtEpochMs)
        assertEquals(5, page.total)
        assertFalse(page.hasMore)
    }

    @Test fun `playlist items old shape`() = runTest {
        enqueue("""{"offset":0,"total":2,"items":[{"added_at":"2024-01-02T03:04:05Z","track":$TRACK},{"track":null}]}""")
        assertEquals(listOf("t1"), catalog.playlistItems("p1").items.map { it.track.id })
    }

    @Test fun `playlist track count from new or old field`() = runTest {
        enqueue("""{"id":"p1","name":"Mine","owner":{"id":"u1","display_name":"Ada"},"snapshot_id":"s1","items":{"total":7,"items":[]}}""")
        enqueue("""{"id":"p2","name":"Old","owner":{"id":"u2"},"snapshot_id":"s2","tracks":{"total":9,"items":[]}}""")
        enqueue("""{"id":"p3","name":"Theirs","description":null,"owner":{"id":"u3"},"snapshot_id":"s3"}""")
        assertEquals(7, catalog.playlist("p1").trackCount)
        assertEquals(9, catalog.playlist("p2").trackCount)
        val theirs = catalog.playlist("p3")
        assertEquals(0, theirs.trackCount)
        assertEquals("", theirs.description)
        assertEquals("u3", theirs.owner.displayName)
    }

    @Test fun `album tracks get album ref from parent`() = runTest {
        enqueue(
            """{"id":"a1","name":"Discovery","album_type":"album","total_tracks":14,"release_date":"2001-03-12",
               "images":[{"url":"https://i/a1","width":640,"height":640}],"artists":[{"id":"ar1","name":"Daft Punk"}],
               "tracks":{"offset":0,"total":14,"next":"n","items":[$SIMPLE_TRACK]}}""",
        )
        val detail = catalog.album("a1")
        assertEquals(AlbumType.Album, detail.album.type)
        assertEquals("2001", detail.album.year)
        val t = detail.tracks.items.single()
        assertEquals("a1", t.album!!.id)
        assertEquals("Discovery", t.album.name)
        assertEquals("https://i/a1", t.album.images.single().url)
        assertEquals(1, t.discNumber)
        assertTrue(detail.tracks.hasMore)

        // Next page reuses the remembered album: no extra /albums/{id} request.
        enqueue("""{"offset":1,"total":14,"next":null,"items":[$SIMPLE_TRACK]}""")
        val more = catalog.albumTracks("a1", offset = 1)
        assertEquals("Discovery", more.items.single().album!!.name)
        take()
        assertEquals("/v1/albums/a1/tracks", take().path)
    }

    @Test fun `album tracks for unseen album fetch the album first`() = runTest {
        enqueue("""{"id":"a9","name":"Homework","album_type":"ALBUM","release_date":"1997","tracks":{"items":[]}}""")
        enqueue("""{"offset":0,"total":1,"items":[$SIMPLE_TRACK]}""")
        assertEquals("Homework", catalog.albumTracks("a9").items.single().album!!.name)
        assertEquals("/v1/albums/a9", take().path)
        assertEquals("/v1/albums/a9/tracks", take().path)
    }

    @Test fun `recently played mapping`() = runTest {
        enqueue(
            """{"items":[
                {"track":$TRACK,"played_at":"2016-12-13T20:44:04.589Z","context":{"type":"playlist","uri":"spotify:playlist:p1"}},
                {"track":$TRACK,"played_at":"2016-12-13T20:40:00Z","context":null},
                {"track":null,"played_at":"2016-12-13T20:40:00Z"}
            ],"next":null,"cursors":{"after":"1","before":"0"}}""",
        )
        val history = catalog.recentlyPlayed(limit = 200)
        assertEquals("50", take().query("limit"))
        assertEquals(2, history.size)
        assertEquals(1481661844589L, history[0].playedAtEpochMs)
        assertEquals("spotify:playlist:p1", history[0].context!!.uri)
        assertEquals("playlist", history[0].context!!.type)
        assertNull(history[1].context)
        assertEquals(listOf("Daft Punk"), history[0].track.artists.map { it.name })
    }

    @Test fun `library save sends comma separated uris query in chunks of 40`() = runTest {
        val uris = (1..41).map { "spotify:track:t$it" }
        enqueue("")
        enqueue("")
        catalog.saveToLibrary(uris)
        val first = take()
        assertEquals("PUT", first.method)
        assertEquals("/v1/me/library", first.path)
        assertEquals(uris.take(40).joinToString(","), first.query("uris"))
        assertTrue(first.url.encodedQuery!!.startsWith("uris=spotify%3Atrack%3At1,spotify%3Atrack%3At2,"))
        assertEquals("spotify:track:t41", take().query("uris"))
    }

    @Test fun `library remove and contains`() = runTest {
        enqueue("")
        catalog.removeFromLibrary(listOf("spotify:album:a1", "spotify:playlist:p1"))
        val del = take()
        assertEquals("DELETE", del.method)
        assertEquals("spotify:album:a1,spotify:playlist:p1", del.query("uris"))

        enqueue("[true,false]")
        assertEquals(listOf(true, false), catalog.libraryContains(listOf("spotify:track:t1", "spotify:artist:ar1")))
        val get = take()
        assertEquals("/v1/me/library/contains", get.path)
        assertEquals("spotify:track:t1,spotify:artist:ar1", get.query("uris"))
    }

    @Test fun `devices mapping`() = runTest {
        enqueue(
            """{"devices":[
                {"id":"d1","is_active":true,"is_private_session":false,"is_restricted":false,"name":"Pixel","type":"Smartphone","volume_percent":59,"supports_volume":true},
                {"id":null,"is_active":false,"name":"Kitchen","type":"AVR","volume_percent":null},
                {"id":"d3","is_active":false,"name":"Thing","type":"GameConsole","volume_percent":10,"supports_volume":false}
            ]}""",
        )
        val devices = catalog.devices()
        assertEquals("/v1/me/player/devices", take().path)
        assertEquals(listOf(DeviceType.Smartphone, DeviceType.Speaker, DeviceType.Other), devices.map { it.type })
        assertTrue(devices[0].isActive)
        assertEquals(59, devices[0].volumePercent)
        assertTrue(devices[0].supportsVolume)
        assertNull(devices[1].id)
        assertFalse(devices[1].supportsVolume)
        assertFalse(devices[2].supportsVolume)
    }

    @Test fun `followed artists follows cursors`() = runTest {
        enqueue("""{"artists":{"items":[${artist("ar1")},${artist("ar2")}],"next":"n","cursors":{"after":"ar2"},"total":5}}""")
        enqueue("""{"artists":{"items":[${artist("ar3")}],"next":null,"cursors":{"after":null},"total":5}}""")
        val artists = catalog.followedArtists(limit = 10)
        assertEquals(listOf("ar1", "ar2", "ar3"), artists.map { it.id })
        val first = take()
        assertEquals("/v1/me/following", first.path)
        assertEquals("artist", first.query("type"))
        assertNull(first.query("after"))
        val second = take()
        assertEquals("ar2", second.query("after"))
        assertEquals("8", second.query("limit"))
    }

    @Test fun `followed artists stops at limit`() = runTest {
        enqueue("""{"artists":{"items":[${artist("ar1")},${artist("ar2")}],"next":"n","cursors":{"after":"ar2"}}}""")
        assertEquals(listOf("ar1", "ar2"), catalog.followedArtists(limit = 2).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun `queue maps tracks and episodes`() = runTest {
        enqueue("""{"currently_playing":$TRACK,"queue":[$EPISODE,null,$TRACK]}""")
        val q = catalog.queue()
        assertEquals("t1", q.current!!.id)
        assertEquals(listOf("e1", "t1"), q.upNext.map { it.id })
    }

    @Test fun `transfer and volume`() = runTest {
        enqueue("", 204)
        enqueue("", 204)
        catalog.transferPlayback("d1", play = false)
        catalog.setVolume(150, "d1")
        val transfer = take()
        assertEquals("PUT", transfer.method)
        assertEquals("""{"device_ids":["d1"],"play":false}""", transfer.bodyText)
        val vol = take()
        assertEquals("/v1/me/player/volume", vol.path)
        assertEquals("100", vol.query("volume_percent"))
        assertEquals("d1", vol.query("device_id"))
    }

    @Test fun `playlist writes use items endpoint`() = runTest {
        enqueue("""{"snapshot_id":"s2"}""", 201)
        enqueue("""{"snapshot_id":"s3"}""")
        enqueue("""{"snapshot_id":"s4"}""")
        assertEquals("s2", catalog.addToPlaylist("p1", listOf("spotify:track:t1"), position = 0))
        assertEquals("s3", catalog.removeFromPlaylist("p1", listOf("spotify:track:t1"), "s2"))
        assertEquals("s4", catalog.reorderPlaylist("p1", 0, 3, "s3"))
        val add = take()
        assertEquals("POST", add.method)
        assertEquals("/v1/playlists/p1/items", add.path)
        assertEquals("""{"uris":["spotify:track:t1"],"position":0}""", add.bodyText)
        val del = take()
        assertEquals("DELETE", del.method)
        assertEquals("""{"items":[{"uri":"spotify:track:t1"}],"snapshot_id":"s2"}""", del.bodyText)
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("""{"range_start":0,"insert_before":3,"range_length":1,"snapshot_id":"s3"}""", put.bodyText)
    }

    @Test fun `create playlist posts to me playlists`() = runTest {
        enqueue("""{"id":"p9","name":"New","description":"","owner":{"id":"u1","display_name":"Ada"},"snapshot_id":"s1","collaborative":false,"items":{"total":0}}""", 201)
        val p = catalog.createPlaylist("New")
        assertEquals("p9", p.id)
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/v1/me/playlists", req.path)
    }

    @Test fun `error body without reason`() = runTest {
        enqueue("""{"error":{"status":403,"message":"Forbidden"}}""", 403)
        val e = assertThrows { catalog.playlistItems("p1") }
        assertEquals(403, e.status)
        assertNull(e.reason)
        assertTrue(e.message!!.contains("Forbidden"))
    }

    @Test fun `token provider failure maps to 401`() = runTest {
        tokens.signedIn = false
        val e = assertThrows { catalog.me() }
        assertEquals(401, e.status)
        assertEquals(0, server.requestCount)
    }

    private suspend fun assertThrows(block: suspend () -> Unit): CatalogException {
        try {
            block()
        } catch (e: CatalogException) {
            return e
        }
        fail("expected CatalogException")
        error("unreachable")
    }

    private class FakeTokens : TokenProvider {
        var n = 1
        var refreshes = 0
        var signedIn = true
        override suspend fun accessToken(): String {
            check(signedIn) { "not signed in" }
            return "access-$n"
        }
        override suspend fun refresh(): String {
            refreshes++
            n++
            return accessToken()
        }
    }

    private companion object {
        fun artist(id: String) = """{"id":"$id","name":"Artist $id","genres":["french house"],"images":[],"type":"artist"}"""

        const val TRACK = """{"id":"t1","name":"One More Time","type":"track","duration_ms":320357,"explicit":false,
            "track_number":1,"is_playable":true,"is_local":false,"artists":[{"id":"ar1","name":"Daft Punk"}],
            "album":{"id":"a1","name":"Discovery","album_type":"album","images":[{"url":"https://i/a1","height":640,"width":640}],
            "release_date":"2001-03-12","total_tracks":14,"artists":[{"id":"ar1","name":"Daft Punk"}]}}"""

        const val SIMPLE_TRACK = """{"id":"t1","name":"One More Time","type":"track","duration_ms":320357,"track_number":1,"disc_number":1,
            "artists":[{"id":"ar1","name":"Daft Punk"}]}"""

        const val EPISODE = """{"id":"e1","name":"Ep 1","type":"episode","duration_ms":1000,"explicit":false,
            "images":[{"url":"https://i/e1"}],"show":{"id":"s1","name":"Show","images":[{"url":"https://i/s1"}]}}"""

        const val SIMPLE_PLAYLIST = """{"id":"p1","name":"Mix","description":"d","collaborative":false,
            "owner":{"id":"u2","display_name":"Bob"},"snapshot_id":"s","images":null,"tracks":{"href":"h","total":42}}"""
    }
}
