package app.remastered.warehouse

import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.WrappedRange
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

// Fixtures follow music-warehouse's real response shapes (captured 2026-10-13) with made-up
// names, ids and counts.
private const val TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC"
private const val ARTIST_ID = "0gxyHStUsqpMadRV0Di1Qt"
private const val TOKEN = "read-token-123"
private val SGT: ZoneId = ZoneId.of("Asia/Singapore")

class WarehouseApiTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private val outcomes = mutableListOf<Pair<String, Problem?>>()
    private var config: WarehouseConfig? = null
    private lateinit var api: WarehouseApi

    @Before fun setUp() {
        server.start()
        config = WarehouseConfig(server.url("/").toString(), TOKEN)
        api = WarehouseApi(OkHttpClient(), { config }, Dispatchers.Unconfined, sleep = { sleeps += it }) { path, e -> outcomes += path to e?.problem }
    }

    @After fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    private fun take(): RecordedRequest = server.takeRequest()
    private val RecordedRequest.path get() = url.encodedPath
    private fun RecordedRequest.query(name: String) = url.queryParameter(name)

    // --- routes ---

    @Test fun `plays sends bearer token and paging, and maps rows`() = runTest {
        enqueue("""{"plays":[
            {"played_at_ms":1791888986386,"track_id":"$TRACK_ID","context_uri":"spotify:album:a1","context_type":"album","source":"api",
             "track_name":"Song One","duration_ms":214161,"isrc":"XX0000000001","album_name":"Album One","image_url":"https://i/1","artists":"Ada, Bea"},
            {"played_at_ms":1500000000000,"track_id":"name:old song","source":"export","track_name":"Old Song","artists":null},
            {"track_id":"no-time"}, null],
          "_meta":{"rows_read":20,"duration_ms":3.07}}""")
        val plays = api.plays(limit = 2, beforeMs = 1791888986387)
        val req = take()
        assertEquals("/api/plays", req.path)
        assertEquals("Bearer $TOKEN", req.headers["Authorization"])
        assertEquals("2", req.query("limit"))
        assertEquals("1791888986387", req.query("before"))
        assertNull(req.query("after"))

        assertEquals(2, plays.size)
        val p = plays[0]
        assertEquals(1791888986386, p.playedAtMs)
        assertEquals("Song One", p.name)
        assertEquals("Ada, Bea", p.artists)
        assertEquals("Album One", p.album)
        assertEquals("https://i/1", p.imageUrl)
        assertEquals(214161L, p.durationMs)
        assertEquals("spotify:album:a1", p.contextUri)
        assertEquals("spotify:track:$TRACK_ID", p.trackUri)
        // Export placeholders aren't playable uris.
        assertNull(plays[1].trackUri)
        assertEquals("", plays[1].artists)
        assertEquals(listOf("api/plays" to null), outcomes)
    }

    @Test fun `plays page passes the cursor back and reads the next one`() = runTest {
        enqueue("""{"plays":[{"played_at_ms":1791888986386,"track_id":"$TRACK_ID","track_name":"Song One"}],
            "next_before":"1791888986386.$TRACK_ID"}""")
        enqueue("""{"plays":[],"next_before":null}""")
        enqueue("""{"plays":[]}""")
        val first = api.playsPage(limit = 1, before = "1791888990000.zzz")
        assertEquals("1791888990000.zzz", take().query("before"))
        assertEquals("1791888986386.$TRACK_ID", first.nextBefore)
        assertTrue(first.cursored)
        assertEquals(1, first.plays.size)

        val last = api.playsPage(limit = 1, before = first.nextBefore)
        assertEquals("1791888986386.$TRACK_ID", take().query("before"))
        assertNull(last.nextBefore)
        assertTrue(last.cursored)

        // A warehouse from before the cursor sends no next_before at all.
        val old = api.playsPage(limit = 1)
        assertNull(take().query("before"))
        assertNull(old.nextBefore)
        assertFalse(old.cursored)
    }

    @Test fun `plays clamps limit`() = runTest {
        enqueue("""{"plays":[]}""")
        api.plays(limit = 10_000)
        assertEquals("500", take().query("limit"))
    }

    @Test fun `daily sends range and zone`() = runTest {
        enqueue("""{"from":"2026-10-01","to":"2026-10-03","tz":"Asia/Singapore",
            "days":[{"day":"2026-10-01","plays":36},{"day":"2026-10-02","plays":0},{"day":"bad","plays":1}],"_meta":{}}""")
        val daily = api.daily(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), SGT)
        val req = take()
        assertEquals("/api/daily", req.path)
        assertEquals("2026-10-01", req.query("from"))
        assertEquals("2026-10-03", req.query("to"))
        assertEquals("Asia/Singapore", req.query("tz"))
        assertEquals("Asia/Singapore", daily.timeZone)
        assertEquals(listOf(36, 0), daily.days.map { it.plays })
        assertEquals(LocalDate.of(2026, 10, 2), daily.days[1].day)
    }

    @Test fun `top artists`() = runTest {
        enqueue("""{"from":"2026-09-14","to":"2026-10-13","tz":"UTC",
            "artists":[{"artist_id":"$ARTIST_ID","name":"Ada","plays":771},{"artist_id":"name:bea","name":"Bea","plays":5},{"plays":1}]}""")
        val top = api.topArtists(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 13), ZoneId.of("UTC"), limit = 2)
        assertEquals("2", take().query("limit"))
        assertEquals(listOf("Ada", "Bea"), top.artists.map { it.name })
        assertEquals("spotify:artist:$ARTIST_ID", top.artists[0].uri)
        assertNull(top.artists[1].uri)
    }

    @Test fun `artist history by name`() = runTest {
        enqueue("""{"artist":{"id":"$ARTIST_ID","name":"Ada"},"plays":20,"minutes":53,
            "first_played":"2024-03-28T20:34:53.000Z","last_played":"2026-09-18T22:14:42.023Z",
            "months":[{"month":"2024-03","plays":2},{"month":"2024-04","plays":0}],
            "top_tracks":[{"id":"$TRACK_ID","name":"Song One","plays":7,"image":"https://i/1"},{"id":null,"name":"Song Two","plays":1,"image":null}]}""")
        val a = api.artist(name = "Ada", zone = SGT)
        val req = take()
        assertEquals("/api/artist", req.path)
        assertEquals("Ada", req.query("name"))
        assertNull(req.query("id"))
        assertEquals("Asia/Singapore", req.query("tz"))
        assertEquals("Ada", a.name)
        assertEquals("spotify:artist:$ARTIST_ID", a.uri)
        assertEquals(20, a.plays)
        assertEquals(53, a.minutes)
        assertEquals(Instant.parse("2024-03-28T20:34:53Z"), a.firstPlayed)
        assertEquals(listOf(YearMonth.of(2024, 3), YearMonth.of(2024, 4)), a.months.map { it.month })
        assertEquals(listOf("Song One", "Song Two"), a.topTracks.map { it.name })
        assertNull(a.topTracks[1].uri)
    }

    @Test fun `unknown artist is empty, not an error`() = runTest {
        enqueue("""{"artist":{"id":null,"name":"Nobody"},"plays":0,"minutes":0,"first_played":null,"last_played":null,"months":[],"top_tracks":[]}""")
        val a = api.artist(name = "Nobody")
        assertNull(a.id)
        assertEquals(0, a.plays)
        assertNull(a.firstPlayed)
        assertTrue(a.months.isEmpty())
    }

    @Test fun `artist needs an id or a name`() = runTest {
        try {
            api.artist(null, " ")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `track history, known and unknown`() = runTest {
        enqueue("""{"track":{"id":"$TRACK_ID","name":"Song One","artist":"Ada","image":"https://i/1"},"plays":7,"minutes":24.4,
            "first_played":"2026-08-28T10:05:14.000Z","last_played":"2026-10-13T10:56:26.386Z",
            "months":[{"month":"2026-08","plays":3},{"month":"2026-09","plays":4}]}""")
        enqueue("""{"track":{"id":"0000000000000000000000","name":null,"artist":null,"image":null},"plays":0,"minutes":0,
            "first_played":null,"last_played":null,"months":[]}""")
        val t = api.track(TRACK_ID, SGT)
        assertEquals(TRACK_ID, take().query("id"))
        assertEquals("Song One", t.name)
        assertEquals("Ada", t.artist)
        assertEquals(24, t.minutes)
        assertEquals(Instant.parse("2026-10-13T10:56:26.386Z"), t.lastPlayed)
        assertEquals(listOf(3, 4), t.months.map { it.plays })

        val unknown = api.track("0000000000000000000000")
        assertNull(unknown.name)
        assertEquals(0, unknown.plays)
    }

    @Test fun `on this day`() = runTest {
        enqueue("""{"date":"2026-10-13","years":[
            {"year":2025,"plays":49,"top_tracks":[{"id":"$TRACK_ID","name":"Song One","artist":"Ada","image":"https://i/1","plays":3},
                                                  {"id":"$TRACK_ID","name":"Song Two","artist":"Bea","image":null,"plays":2}]},
            {"year":2019,"plays":1,"top_tracks":[]}]}""")
        val day = api.onThisDay(LocalDate.of(2026, 10, 13), SGT)
        val req = take()
        assertEquals("/api/on-this-day", req.path)
        assertEquals("2026-10-13", req.query("date"))
        assertEquals(LocalDate.of(2026, 10, 13), day.date)
        assertEquals(listOf(2025, 2019), day.years.map { it.year })
        assertEquals(49, day.years[0].plays)
        assertEquals(listOf("Song One", "Song Two"), day.years[0].topTracks.map { it.name })
        assertNull(day.years[0].topTracks[1].imageUrl)
    }

    @Test fun `home rows`() = runTest {
        enqueue("""{"tz":"Asia/Singapore","hour":20,
            "shortcuts":[{"uri":"spotify:album:$TRACK_ID","type":"album","name":"Album One","image":"https://i/1","artist":"Ada","sessions":14,"last_played_ms":1760554020000},
                         {"uri":"spotify:playlist:$TRACK_ID","type":"playlist","name":null,"image":null,"artist":null,"sessions":3},
                         {"uri":"spotify:user:me:collection","name":"Liked"},
                         {"uri":"spotify:album:$TRACK_ID","name":"Again"},
                         null],
            "jump_back_in":[{"uri":"spotify:artist:$TRACK_ID","name":"  "}],
            "rediscover":[]}""")
        val feed = api.home(SGT)
        val req = take()
        assertEquals("/api/home", req.path)
        assertEquals("Asia/Singapore", req.query("tz"))
        assertEquals(20, feed.hour)
        // Only playlists, albums and artists, each once; the type comes from the uri.
        assertEquals(listOf("album", "playlist"), feed.shortcuts.map { it.type })
        assertEquals("Ada", feed.shortcuts[0].artist)
        assertEquals(1760554020000, feed.shortcuts[0].lastPlayedMs)
        assertNull(feed.shortcuts[1].name)
        assertNull(feed.jumpBackIn.single().name)
        assertEquals(emptyList<Any>(), feed.aroundNow)
    }

    @Test fun `on repeat`() = runTest {
        enqueue("""{"as_of":"2026-10-10T16:00:00.000Z","window_days":14,
            "tracks":[{"track_id":"$TRACK_ID","name":"Song One","artists":"Ada","album":"Album One","image":"https://i/1",
                       "plays":23,"days":12,"daily":[2,4,3,1,1,1,5,0,1,1,1,1,0,2]}],
            "albums":[{"name":"Album One","artist":"Ada","image":"https://i/1","tracks_on_repeat":13,"plays":193}]}""")
        val r = api.onRepeat()
        assertEquals("/api/stats/on-repeat", take().path)
        assertEquals(Instant.parse("2026-10-10T16:00:00Z"), r.asOf)
        assertEquals(14, r.windowDays)
        assertEquals(14, r.tracks.single().daily.size)
        assertEquals("spotify:track:$TRACK_ID", r.tracks.single().uri)
        assertEquals(13, r.albums.single().tracksOnRepeat)
    }

    @Test fun `rhythm`() = runTest {
        val hours = List(24) { "0.04" }.joinToString(",")
        enqueue("""{"as_of":"2026-10-05T16:00:00.000Z","range":{"from":"2020-07","to":"2026-10"},
            "streaks":[{"threshold":1,"current_weeks":3,"longest":{"days":120,"weeks":17,"from":"2021-01","to":"2021-05","running":false},
                        "top":[{"days":null,"weeks":3,"from":"2026-09","to":"2026-10","running":true}],"active_share":0.912}],
            "eras":[{"key":"newcastle","label":"Newcastle","from":"2020-07","to":"2026-07","hour_share":[$hours],
                     "grid":[[0.1,0.2],[0,1]],"hours_available_from":null,"grid_available_from":null},
                    {"key":"singapore","label":"Singapore","from":"2026-07","to":null,"hour_share":null,"grid":null,
                     "hours_available_from":null,"grid_available_from":"2027-01"}],
            "years":[{"year":"2026","partial":true,"streaks":[],"eras":[{"key":"singapore","label":"Singapore","from":"2026-07"}]}]}""")
        val r = api.rhythm()
        assertEquals(YearMonth.of(2020, 7), r.from)
        val ones = r.streaks.single()
        assertEquals(1, ones.threshold)
        assertEquals(120, ones.longest?.days)
        assertNull(ones.top.single().days)
        assertTrue(ones.top.single().running)
        assertEquals(24, r.eras[0].hourShare?.size)
        assertEquals(listOf(listOf(0.1, 0.2), listOf(0.0, 1.0)), r.eras[0].grid)
        assertNull(r.eras[1].to)
        assertNull(r.eras[1].hourShare)
        assertEquals(YearMonth.of(2027, 1), r.eras[1].gridAvailableFrom)
        assertEquals(2026, r.years.single().year)
        assertTrue(r.years.single().partial)
    }

    @Test fun `top over time`() = runTest {
        enqueue("""{"as_of":"2026-10-05T16:00:00.000Z","attribution":"primary-artist",
            "quarters":[{"period":"2026-Q3","partial":false,"plays":4000,"artists":[{"name":"Ada","plays":500,"share":0.125}],
                         "tracks":[{"name":"Song One","artist":"Ada","plays":40,"image":null}]}],
            "years":[{"period":"2026","partial":true,"plays":9000,"artists":[],"tracks":[]}],
            "leaders":[{"name":"Ada","plays":5000,"share":[0.1,0.125]}]}""")
        val t = api.topOverTime()
        assertEquals("2026-Q3", t.quarters.single().period)
        assertEquals(0.125, t.quarters.single().artists.single().share, 0.0)
        assertNull(t.quarters.single().tracks.single().imageUrl)
        assertTrue(t.years.single().partial)
        assertEquals(listOf(0.1, 0.125), t.leaders.single().share)
    }

    @Test fun `eras, ready and not yet`() = runTest {
        enqueue("""{"as_of":"2026-10-01T00:00:00.000Z","move_month":"2026-07","window_months":2,
            "singapore":{"from":"2026-08","to":"2026-09"},"before":{"from":"2026-05","to":"2026-06"},"ready":true,
            "summary":{"known_artist_share":0.967,"new_artists":76,"artists":322,"change":0.735,
                       "typical_change":{"median":0.447,"min":0.218,"max":0.844,"windows":35,"exceeded_by":1}},
            "top":{"newcastle":[{"artist":"Ada","share":0.116}],"before":[{"artist":"Bea","share":0.6}],"singapore":[{"artist":"Cy","share":0.125}]},
            "risers":[{"artist":"Cy","before":0,"after":0.125}],"fallers":[{"artist":"Bea","before":0.6,"after":0.12}]}""")
        enqueue("""{"as_of":"2026-10-01T00:00:00.000Z","move_month":null,"window_months":0,"singapore":null,"before":null,
            "ready":false,"summary":null,"top":{"newcastle":[],"before":[],"singapore":[]},"risers":[],"fallers":[]}""")
        val e = api.eras()
        assertEquals("/api/stats/eras", take().path)
        assertTrue(e.ready)
        assertEquals(YearMonth.of(2026, 7), e.moveMonth)
        assertEquals(YearMonth.of(2026, 8), e.singapore?.from)
        assertEquals(35, e.summary?.typicalChange?.windows)
        assertEquals("Ada", e.topNewcastle.single().artist)
        assertEquals(0.125, e.risers.single().after, 0.0)

        val empty = api.eras()
        assertFalse(empty.ready)
        assertNull(empty.summary)
        assertNull(empty.moveMonth)
        assertTrue(empty.topSingapore.isEmpty())
    }

    @Test fun `top albums`() = runTest {
        enqueue("""{"as_of":"2026-10-05T16:00:00.000Z","min_songs":4,
            "albums":[{"name":"Album One","artist":"Ada","image":"https://i/1","plays":900,"songs":12,"from":"2020-08","to":"2026-10"}],
            "years":[{"year":"2025","partial":false,"albums":[{"name":"Album One","artist":"Ada","image":null,"plays":90,"songs":5,"from":"2025-01","to":"2025-12"}]}]}""")
        val a = api.topAlbums()
        assertEquals("/api/stats/albums", take().path)
        assertEquals(4, a.minSongs)
        assertEquals(12, a.albums.single().songs)
        assertEquals(YearMonth.of(2020, 8), a.albums.single().from)
        assertEquals(2025, a.years.single().year)
    }

    @Test fun `wrapped ranges`() = runTest {
        enqueue("""{"ranges":[
            {"from":"2026-01-01","to":"2026-10-11","label":"2026 so far","kind":"year_to_date","computed":false},
            {"from":"2025-01-01","to":"2025-12-31","label":"2025","kind":"year","computed":true},
            {"from":"2020-07-01","to":"2026-10-11","label":"All time","kind":"all_time"},
            {"from":"nope","to":"2025-12-31","label":"Broken","kind":"year"}]}""")
        val ranges = api.wrappedRanges()
        assertEquals("/api/wrapped/ranges", take().path)
        assertEquals(listOf("2026 so far", "2025", "All time"), ranges.map { it.label })
        assertEquals(WrappedRange.Kind.YearToDate, ranges[0].kind)
        assertEquals(WrappedRange.Kind.Year, ranges[1].kind)
        assertEquals(WrappedRange.Kind.Other, ranges[2].kind)
        assertTrue(ranges[1].computed)
        assertFalse(ranges[2].computed)
        assertEquals(LocalDate.of(2026, 10, 11), ranges[0].to)
    }

    @Test fun `wrapped`() = runTest {
        enqueue(WRAPPED)
        val w = api.wrapped(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
        val req = take()
        assertEquals("/api/wrapped", req.path)
        assertEquals("2025-01-01", req.query("from"))
        assertEquals("2025-12-31", req.query("to"))
        assertEquals(365, w.days)
        assertEquals(Instant.parse("2026-10-13T08:00:00Z"), w.computedAt)
        assertEquals(41234, w.totals.minutes)
        assertEquals(12, w.totals.skips)
        assertEquals(LocalDate.of(2025, 3, 14), w.biggestDay?.date)
        assertEquals(24, w.hours.size)
        assertEquals(7, w.weekdays.size)
        assertEquals("Ada", w.topArtists.single().name)
        assertEquals(0.21, w.topArtists.single().share, 0.0)
        assertEquals(YearMonth.of(2025, 6), w.topSongs.single().peakMonth)
        assertEquals(LocalDate.of(2025, 1, 2), w.topSongs.single().firstPlayed)
        assertEquals(3, w.topAlbums.single().songs)
        assertEquals(YearMonth.of(2025, 1), w.months.single().month)
        assertEquals(1, w.discoveries.top.size)
        assertEquals(2004..2008, w.listeningAge?.window)
        assertEquals(listOf(2000, 2010), w.listeningAge?.decades?.map { it.decade })
        assertEquals(DAY_RUN_DAYS, w.streak?.days)
    }

    @Test fun `wrapped without listening age or streak`() = runTest {
        enqueue("""{"range":{"from":"2025-01-01","to":"2025-01-02","days":2},"computed_at":"x",
            "totals":{"minutes":0,"plays":0,"skips":0,"days_listened":0,"artists":0,"songs":0},
            "biggest_day":null,"hours":[],"weekdays":[],"top_artists":[],"top_songs":[],"top_albums":[],"months":[],
            "discoveries":{"artists":0,"songs":0,"share":0,"top":[]},"listening_age":null,"streak":null}""")
        val w = api.wrapped(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
        assertNull(w.computedAt)
        assertNull(w.biggestDay)
        assertNull(w.listeningAge)
        assertNull(w.streak)
    }

    @Test fun `unknown fields and missing optional fields are tolerated`() = runTest {
        enqueue("""{"as_of":"2026-10-10T16:00:00.000Z","brand_new":{"nested":[1,2]},
            "tracks":[{"track_id":"$TRACK_ID","name":"Song One","future_field":true}]}""")
        val r = api.onRepeat()
        assertEquals(14, r.windowDays)
        assertTrue(r.albums.isEmpty())
        assertEquals("", r.tracks.single().artists)
        assertTrue(r.tracks.single().daily.isEmpty())
    }

    // --- failures ---

    @Test fun `503 with Retry-After waits and retries`() = runTest {
        enqueue("""{"error":"Stats are being computed; try again shortly."}""", 503, mapOf("Retry-After" to "2"))
        enqueue("""{"error":"Stats are being computed; try again shortly."}""", 503, mapOf("Retry-After" to "3"))
        enqueue("""{"as_of":null,"tracks":[],"albums":[]}""")
        api.onRepeat()
        assertEquals(listOf(2000L, 3000L), sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test fun `503 retries are bounded`() = runTest {
        repeat(WarehouseApi.MAX_BUSY_RETRIES + 1) { enqueue("""{"error":"computing"}""", 503, mapOf("Retry-After" to "1")) }
        val e = assertThrows { api.rhythm() }
        assertEquals(Problem.Busy, e.problem)
        assertEquals(503, e.status)
        assertEquals(1L, e.retryAfterS)
        assertEquals(WarehouseApi.MAX_BUSY_RETRIES + 1, server.requestCount)
    }

    @Test fun `503 asking for too long a wait fails at once`() = runTest {
        enqueue("""{"error":"computing"}""", 503, mapOf("Retry-After" to "3600"))
        val e = assertThrows { api.rhythm() }
        assertEquals(Problem.Busy, e.problem)
        assertEquals(3600L, e.retryAfterS)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun `503 without Retry-After is a server error`() = runTest {
        enqueue("""{"error":"Spotify connection needs re-authorization.","needs_reauth":true}""", 503)
        val e = assertThrows { api.onRepeat() }
        assertEquals(Problem.ServerError, e.problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun `cancelling during a 503 wait stops retrying`() = runTest {
        val waiting = CompletableDeferred<Long>()
        val slow = WarehouseApi(OkHttpClient(), { config }, Dispatchers.Unconfined, sleep = { waiting.complete(it); awaitCancellation() })
        enqueue("""{"error":"computing"}""", 503, mapOf("Retry-After" to "10"))
        val job = launch(Dispatchers.Default) { slow.onRepeat() }
        assertEquals(10_000L, waiting.await())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun `401 is unauthorized`() = runTest {
        enqueue("""{"error":"Unauthorized"}""", 401)
        val e = assertThrows { api.plays() }
        assertEquals(Problem.Unauthorized, e.problem)
        assertEquals(401, e.status)
        assertTrue(e.userMessage.contains("token"))
        assertEquals(listOf("api/plays" to Problem.Unauthorized), outcomes)
    }

    @Test fun `403 keeps the warehouse's reason`() = runTest {
        enqueue("""{"error":"This range has not been computed. A read token computes only the ranges at /api/wrapped/ranges."}""", 403)
        val e = assertThrows { api.wrapped(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1)) }
        assertEquals(Problem.Forbidden, e.problem)
        assertTrue(e.userMessage.startsWith("This range has not been computed"))
    }

    @Test fun `400 404 and 500 map to their problems`() = runTest {
        enqueue("""{"error":"date must be a real YYYY-MM-DD date"}""", 400)
        enqueue("""{"error":"Not found"}""", 404)
        enqueue("<html>oops</html>", 500)
        assertEquals("date must be a real YYYY-MM-DD date", assertThrows { api.onThisDay(LocalDate.of(2026, 2, 1)) }.userMessage)
        assertEquals(Problem.NotFound, assertThrows { api.wrappedRanges() }.problem)
        val e = assertThrows { api.topAlbums() }
        assertEquals(Problem.ServerError, e.problem)
        assertEquals(500, e.status)
    }

    @Test fun `no response is unreachable`() = runTest {
        server.close()
        val e = assertThrows { api.plays() }
        assertEquals(Problem.Unreachable, e.problem)
        assertNull(e.status)
        assertEquals(Problem.Unreachable, outcomes.single().second)
    }

    @Test fun `unreadable 200 is a bad response`() = runTest {
        enqueue("""{"plays":"not a list"}""")
        assertEquals(Problem.BadResponse, assertThrows { api.plays() }.problem)
        enqueue("""{"range":{"from":"2025-01-01"}}""")
        assertEquals(Problem.BadResponse, assertThrows { api.wrapped(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2)) }.problem)
    }

    @Test fun `not configured sends nothing`() = runTest {
        config = null
        assertEquals(Problem.NotConfigured, assertThrows { api.plays() }.problem)
        assertEquals(0, server.requestCount)
        assertTrue(outcomes.isEmpty())
    }

    @Test fun `base url with a path prefix`() = runTest {
        config = WarehouseConfig(server.url("/warehouse/").toString(), TOKEN)
        enqueue("""{"plays":[]}""")
        api.plays()
        assertEquals("/warehouse/api/plays", take().path)
    }

    @Test fun `serves tells new routes from an older warehouse`() = runTest {
        enqueue("""{"error":"id is required"}""", 400)
        enqueue("""{"error":"Not found"}""", 404)
        enqueue("""{"error":"date must be a real YYYY-MM-DD date"}""", 400)
        enqueue("""{"error":"Unknown IANA timezone: -"}""", 400)
        assertEquals(true, api.serves(WarehouseFeature.TrackHistory))
        assertEquals("/api/track", take().path)
        assertEquals(false, api.serves(WarehouseFeature.ArtistHistory))
        take()
        assertEquals(true, api.serves(WarehouseFeature.OnThisDay))
        assertEquals("-", take().query("date"))
        assertEquals(true, api.serves(WarehouseFeature.Home))
        assertEquals("-", take().query("tz"))
        server.close()
        assertNull(api.serves(WarehouseFeature.WrappedRanges))
    }

    @Test fun `request log never sees the token`() = runTest {
        val log = app.remastered.spotify.web.RequestLog()
        val logged = WarehouseApi(OkHttpClient.Builder().addInterceptor(log).build(), { config }, Dispatchers.Unconfined)
        enqueue("""{"plays":[]}""")
        logged.plays(limit = 1)
        val entry = log.entries.value.single()
        assertEquals("/api/plays", entry.path)
        assertFalse(entry.toString().contains(TOKEN))
        assertFalse(WarehouseConfig("https://w/", TOKEN).toString().contains(TOKEN))
    }

    private suspend fun assertThrows(block: suspend () -> Unit): WarehouseException {
        try {
            block()
        } catch (e: WarehouseException) {
            return e
        }
        fail("expected WarehouseException")
        error("unreachable")
    }
}

private const val DAY_RUN_DAYS = 41

private val WRAPPED = """
{"range":{"from":"2025-01-01","to":"2025-12-31","days":365},"computed_at":"2026-10-13T08:00:00.000Z",
 "totals":{"minutes":41234,"plays":15000,"skips":12,"days_listened":360,"artists":900,"songs":4000},
 "biggest_day":{"date":"2025-03-14","minutes":600},
 "hours":[${List(24) { it * 10 }.joinToString(",")}],"weekdays":[1,2,3,4,5,6,7],
 "top_artists":[{"name":"Ada","minutes":8000,"plays":2500,"share":0.21,"days":200,"longest_run_days":30,"top_song":"Song One","image":"https://i/1"}],
 "top_songs":[{"name":"Song One","artist":"Ada","album":"Album One","image":"https://i/1","plays":149,"minutes":500,
               "peak_month":"2025-06","first_played":"2025-01-02"}],
 "top_albums":[{"name":"Album One","artist":"Ada","image":null,"plays":400,"minutes":1300,"songs":3}],
 "months":[{"month":"2025-01","minutes":3000,"top_artist":"Ada","top_song":"Song One","image":"https://i/1"}],
 "discoveries":{"artists":120,"songs":800,"share":0.15,"top":[{"name":"Bea","minutes":400,"image":null}]},
 "listening_age":{"age":35,"median_year":2009,"window":[2004,2008],"coverage":0.9,"decades":[{"decade":2000,"share":0.6},{"decade":2010,"share":0.4}]},
 "streak":{"days":$DAY_RUN_DAYS,"from":"2025-02-01","to":"2025-03-13"}}
"""
