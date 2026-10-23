package app.remastered.warehouse

import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// /api/radio as music-warehouse's src/radio.ts answers it, with made-up names, ids and counts.
private const val SEED = "4uLU6hMCjMI75M1A2tKUQC"
private const val NEAR = "0aB1cD2eF3gH4iJ5kL6mN7"
private const val ALSO = "7zY6xW5vU4tS3rQ2pO1nM0"

class RadioApiTest {
    private val server = MockWebServer()
    private val outcomes = mutableListOf<Pair<String, Problem?>>()
    private lateinit var api: WarehouseApi

    @Before fun setUp() {
        server.start()
        val config = WarehouseConfig(server.url("/").toString(), "read-token-123")
        api = WarehouseApi(OkHttpClient(), { config }, Dispatchers.Unconfined, sleep = {}) { path, e -> outcomes += path to e?.problem }
    }

    @After fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test fun `track radio sends the seed and limit, and keeps only playable tracks`() = runTest {
        enqueue("""{"seed":{"type":"track","id":"$SEED","name":"Seed Song","artist":"Ada"},
          "tracks":[
            {"id":"$NEAR","name":"Near Song","artist":"Bea","artist_id":"1aaaaaaaaaaaaaaaaaaaaa","album":"Near Album",
             "image":"https://i.scdn.co/image/near","sessions":7,"score":1.874,"added_later":true},
            {"id":"name:Old Song|Cy","name":"Old Song","artist":"Cy","artist_id":"name:cy","album":null,"image":null,"sessions":2,"score":0.5},
            {"id":"$ALSO","name":null,"sessions":null},
            {"id":"$NEAR","name":"Near Song again","sessions":1},
            {"name":"No id"},
            null]}""")
        val radio = api.radio(trackId = SEED, limit = 500)

        val req = server.takeRequest()
        assertEquals("/api/radio", req.url.encodedPath)
        assertEquals(SEED, req.url.queryParameter("track"))
        assertNull(req.url.queryParameter("artist"))
        assertEquals("100", req.url.queryParameter("limit"))

        assertEquals("Seed Song", radio.seedName)
        assertEquals(listOf(NEAR, ALSO), radio.tracks.map { it.id })
        val near = radio.tracks[0]
        assertEquals("spotify:track:$NEAR", near.uri)
        assertEquals("Near Song", near.name)
        assertEquals("Bea", near.artist)
        assertEquals("Near Album", near.album)
        assertEquals("https://i.scdn.co/image/near", near.image)
        assertEquals(7, near.sessions)
        // Missing or null fields degrade one value, not the radio.
        assertEquals("", radio.tracks[1].name)
        assertEquals(0, radio.tracks[1].sessions)
    }

    @Test fun `artist radio sends the artist, and an empty answer is an empty radio`() = runTest {
        enqueue("""{"seed":{"type":"artist","id":"0gxyHStUsqpMadRV0Di1Qt","name":null},"tracks":[]}""")
        val radio = api.radio(artistId = "0gxyHStUsqpMadRV0Di1Qt")
        val req = server.takeRequest()
        assertEquals("0gxyHStUsqpMadRV0Di1Qt", req.url.queryParameter("artist"))
        assertNull(req.url.queryParameter("track"))
        assertEquals("40", req.url.queryParameter("limit"))
        assertNull(radio.seedName)
        assertTrue(radio.tracks.isEmpty())

        enqueue("{}")
        assertTrue(api.radio(trackId = SEED).tracks.isEmpty())
    }

    @Test fun `a warehouse without the route is NotFound, and reported for the feature`() = runTest {
        enqueue("""{"error":"Not found"}""", code = 404)
        try {
            api.radio(trackId = SEED)
            fail("expected a 404")
        } catch (e: WarehouseException) {
            assertEquals(Problem.NotFound, e.problem)
        }
        assertEquals(listOf(WarehouseFeature.Radio.path to Problem.NotFound), outcomes)
    }

    @Test fun `serves probes the route without a seed`() = runTest {
        enqueue("""{"error":"give exactly one of track or artist"}""", code = 400)
        assertEquals(true, api.serves(WarehouseFeature.Radio))
        val req = server.takeRequest()
        assertEquals("/api/radio", req.url.encodedPath)
        assertNull(req.url.query)

        enqueue("""{"error":"Not found"}""", code = 404)
        assertEquals(false, api.serves(WarehouseFeature.Radio))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a radio needs exactly one seed`() = runTest { api.radio(trackId = SEED, artistId = "x") }
}
