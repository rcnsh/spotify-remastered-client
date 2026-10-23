package app.remastered.warehouse

import app.remastered.core.warehouse.ConcertArtist
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseFeature
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Fixtures follow music-warehouse's /api/concerts and /api/search shapes (src/concerts.ts,
// src/search.ts there) with made-up names, ids, places and counts.
private const val ARTIST_ID = "0gxyHStUsqpMadRV0Di1Qt"
private const val TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC"
private const val TOKEN = "read-token-123"

class WarehouseConcertsSearchTest {
    private val server = MockWebServer()
    private lateinit var api: WarehouseApi

    @Before fun setUp() {
        server.start()
        val config = WarehouseConfig(server.url("/").toString(), TOKEN)
        api = WarehouseApi(OkHttpClient(), { config }, Dispatchers.Unconfined, sleep = {})
    }

    @After fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun take(): RecordedRequest = server.takeRequest()

    // --- /api/concerts ---

    @Test fun `concerts map regions, billed artists and dates`() = runTest {
        enqueue("""{
          "regions":[{"key":"north","label":"Northtown"},{"key":"bare"},{"label":"no key"}],
          "checked":{"north":"2026-10-20T01:23:04.000Z"},
          "concerts":[
            {"region":"north","date":"2026-11-02","time":"19:30","artists":"Ada Lark, Bea Moss",
             "artist_list":[{"id":"$ARTIST_ID","name":"Ada Lark"},{"id":"name:bea moss","name":"Bea Moss"}],
             "name":"Ada Lark: Autumn Tour","venue":"The Hall","city":"Northtown","status":"onsale",
             "url":"https://tickets.example/event/1","first_seen":"2026-10-01T01:23:04.000Z"},
            {"region":"north","date":null,"time":null,"artists":"Cy Fen","artist_list":[{"id":null,"name":"Cy Fen"}],
             "name":"Cy Fen Live","venue":null,"city":null,"status":"onsale","url":"http://insecure.example/2","first_seen":null}
          ]}""")
        val c = api.concerts()
        val req = take()
        assertEquals("/api/concerts", req.url.encodedPath)
        assertEquals("Bearer $TOKEN", req.headers["Authorization"])

        assertEquals(listOf("north" to "Northtown", "bare" to "bare"), c.regions.map { it.key to it.label })
        assertEquals(2, c.concerts.size)
        val first = c.concerts[0]
        assertEquals("Ada Lark: Autumn Tour", first.name)
        assertEquals(listOf(ConcertArtist(ARTIST_ID, "Ada Lark"), ConcertArtist("name:bea moss", "Bea Moss")), first.artists)
        assertEquals("spotify:artist:$ARTIST_ID", first.artists[0].uri)
        assertNull(first.artists[1].uri)
        assertEquals(LocalDate.of(2026, 11, 2), first.date)
        assertEquals("19:30", first.time)
        assertEquals("The Hall", first.venue)
        assertEquals("Northtown", first.city)
        assertEquals("https://tickets.example/event/1", first.url)
        assertEquals(Instant.parse("2026-10-01T01:23:04Z"), first.firstSeen)
        assertEquals("Ada Lark, Bea Moss", first.headliners)

        val second = c.concerts[1]
        assertNull(second.date)
        assertNull(second.venue)
        assertNull("only https ticket links are kept", second.url)
    }

    @Test fun `concerts from rows stored before artist_list fall back to the joined names`() = runTest {
        enqueue("""{"regions":[],"concerts":[
            {"date":"2026-12-01","artists":"Ada Lark, Bea Moss","artist_list":null,"name":"Double Bill"},
            {"date":"2026-12-02","artists":"","artist_list":[],"name":""},
            {"date":"not a date","time":"8pm","artists":"Cy Fen"},
            null]}""")
        val c = api.concerts()
        assertEquals(2, c.concerts.size)
        assertEquals(listOf(ConcertArtist(null, "Ada Lark"), ConcertArtist(null, "Bea Moss")), c.concerts[0].artists)
        val loose = c.concerts[1]
        assertEquals("Cy Fen", loose.name)
        assertNull(loose.date)
        assertNull(loose.time)
    }

    @Test fun `no regions configured is an empty list`() = runTest {
        enqueue("""{"regions":[],"checked":{},"concerts":[]}""")
        val c = api.concerts()
        assertTrue(c.regions.isEmpty())
        assertTrue(c.concerts.isEmpty())
    }

    // --- /api/search ---

    @Test fun `search sends the query and maps artists and songs`() = runTest {
        enqueue("""{"q":"lark","artists":[
              {"id":"$ARTIST_ID","name":"Ada Lark","plays":412},
              {"id":null,"name":"Larkspur","plays":3},
              {"id":"x","name":null,"plays":1}],
            "tracks":[
              {"id":"$TRACK_ID","name":"Skylark","artist":"Ada Lark","plays":97},
              {"id":"name:larks|cy fen","name":"Larks","artist":null,"plays":2},
              {"id":null,"name":"No id","plays":5}]}""")
        val r = api.search("  lark ", limit = 80)
        val req = take()
        assertEquals("/api/search", req.url.encodedPath)
        assertEquals("lark", req.url.queryParameter("q"))
        assertEquals("50", req.url.queryParameter("limit"))

        assertEquals("lark", r.query)
        assertEquals(listOf("Ada Lark", "Larkspur"), r.artists.map { it.name })
        assertEquals(412, r.artists[0].plays)
        assertNull(r.artists[1].id)
        assertEquals(2, r.tracks.size)
        assertEquals("spotify:track:$TRACK_ID", r.tracks[0].uri)
        assertEquals("Ada Lark", r.tracks[0].artist)
        assertNull(r.tracks[1].uri)
    }

    @Test fun `search caps the query at the warehouse's 200 characters`() = runTest {
        enqueue("""{"artists":[],"tracks":[]}""")
        val r = api.search("a".repeat(250))
        assertEquals(200, take().url.queryParameter("q")!!.length)
        assertEquals(200, r.query.length)
    }

    @Test fun `search needs a query`() = runTest {
        try {
            api.search("   ")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `a warehouse without the routes answers 404`() = runTest {
        enqueue("""{"error":"Not found"}""", 404)
        try {
            api.concerts()
            fail("expected WarehouseException")
        } catch (e: WarehouseException) {
            assertEquals(Problem.NotFound, e.problem)
        }
    }

    @Test fun `serves probes both routes without a database read`() = runTest {
        enqueue("""{"error":"q is required"}""", 400)
        enqueue("""{"error":"no concert regions are configured"}""", 400)
        enqueue("""{"error":"Not found"}""", 404)
        assertEquals(true, api.serves(WarehouseFeature.Search))
        take().let { assertEquals("/api/search", it.url.encodedPath); assertNull(it.url.queryParameter("q")) }
        assertEquals(true, api.serves(WarehouseFeature.Concerts))
        assertEquals("-", take().url.queryParameter("region"))
        assertEquals(false, api.serves(WarehouseFeature.Concerts))
    }
}
