package app.remastered.warehouse

import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.WarehouseState
import app.remastered.spicylyrics.LyricsCache
import app.remastered.spicylyrics.LyricsEndpoint
import app.remastered.spicylyrics.SpicyLyrics
import app.remastered.spicylyrics.SpicyLyricsApi
import app.remastered.spicylyrics.SpicyLyricsApi.Response
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// /api/lyrics as music-warehouse's src/lyrics.ts answers it: Spicy Lyrics' body passed on, or its
// own {"error"}. The lyrics are made up.
private const val ID = "0aB1cD2eF3gH4iJ5kL6mN7"
private const val URI = "spotify:track:$ID"
private val LYRICS = """
{"Body":{"Type":"Line","id":"$ID","source":"spicy_lyrics","Content":[{"Type":"Vocal","Text":"A made-up line","StartTime":1,"EndTime":2}],
 "UploadAttribution":{"Uploader":{"id":"9","username":"someone","url":"https://spicylyrics.org/uid/9"}}},"Status":200,"Type":"object"}
""".trimIndent()
private const val NO_LYRICS = """{"Body":{"error":"lyrics_not_found","message":"No lyrics."},"Status":404,"Type":"object"}"""
private const val OFF = """{"error":"Not found"}"""

class WarehouseLyricsTest {
    private val warehouse = MockWebServer()
    private val spicy = MockWebServer()
    private lateinit var dir: File
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        warehouse.start()
        spicy.start()
        dir = Files.createTempDirectory("lyrics").toFile()
    }

    @After fun tearDown() {
        warehouse.close()
        spicy.close()
        dir.deleteRecursively()
    }

    private fun services() = WarehouseServices(
        InMemoryWarehouseConfigStore(WarehouseConfig(warehouse.url("/").toString(), "read-token-123")),
        OkHttpClient(), io = Dispatchers.Unconfined, sleep = {},
    )

    private fun direct(key: String = "sl_pk_test") = SpicyLyricsApi(OkHttpClient(), key, spicy.url("/").toString(), Dispatchers.Unconfined)

    /** As AppContainer wires it: the warehouse while it serves lyrics, then the built-in key. */
    private fun lyrics(w: WarehouseServices, api: SpicyLyricsApi = direct()) = SpicyLyrics(
        endpoints = {
            buildList {
                if (w.supports(WarehouseFeature.Lyrics)) add(w.lyrics)
                if (api.isConfigured) add(api)
            }
        },
        cache = LyricsCache(dir) { now }, now = { now }, io = Dispatchers.Unconfined,
    )

    private fun MockWebServer.reply(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    @Test fun `asks the warehouse with its token and passes Spicy Lyrics' body on`() = runTest {
        warehouse.reply(LYRICS, headers = mapOf("Age" to "3600"))
        val w = services()
        assertEquals(Response.Body(LYRICS, 3_600_000), w.lyrics.fetch(ID))
        val req = warehouse.takeRequest()
        assertEquals("/api/lyrics", req.url.encodedPath)
        assertEquals(ID, req.url.queryParameter("track"))
        assertEquals("Bearer read-token-123", req.headers["Authorization"])
        assertEquals(WarehouseState.Ready, w.state.value)
    }

    @Test fun `Spicy Lyrics' no-lyrics 404 is an answer, the warehouse's own is a missing route`() = runTest {
        val w = services()
        warehouse.reply(NO_LYRICS, 404, mapOf("Age" to "60"))
        assertEquals(Response.NotFound(60_000), w.lyrics.fetch(ID))
        assertTrue(w.supports(WarehouseFeature.Lyrics))

        warehouse.reply(OFF, 404)
        assertEquals(Response.Unavailable, w.lyrics.fetch(ID))
        assertFalse(w.supports(WarehouseFeature.Lyrics))
        assertEquals(WarehouseState.Ready, w.state.value)
    }

    @Test fun `a rejected token falls through and says so`() = runTest {
        val w = services()
        warehouse.reply("""{"error":"Unauthorized"}""", 401)
        assertEquals(Response.Unavailable, w.lyrics.fetch(ID))
        assertEquals(WarehouseState.Unauthorized, w.state.value)
    }

    @Test fun `rate limits and upstream failures map like Spicy Lyrics' own`() = runTest {
        val w = services()
        warehouse.reply("""{"Body":{"error":"rate_limited"}}""", 429, mapOf("Retry-After" to "20"))
        assertEquals(Response.Failed(LyricsResult.Problem.RateLimited, 20_000), w.lyrics.fetch(ID))
        warehouse.reply("""{"error":"Lyrics upstream: key rejected"}""", 502)
        assertEquals(Response.Failed(LyricsResult.Problem.ServiceDown), w.lyrics.fetch(ID))
        warehouse.reply("""{"Body":{"error":"server_busy"}}""", 503, mapOf("Retry-After" to "5"))
        assertEquals(Response.Failed(LyricsResult.Problem.ServiceDown, 5_000), w.lyrics.fetch(ID))
        // None of these says the route is missing.
        assertTrue(w.supports(WarehouseFeature.Lyrics))
    }

    @Test fun `the probe sends no track, so Spicy Lyrics is never asked`() = runTest {
        val api = WarehouseApi(OkHttpClient(), { WarehouseConfig(warehouse.url("/").toString(), "t") }, Dispatchers.Unconfined, sleep = {})
        warehouse.reply("""{"error":"track is required"}""", 400)
        assertEquals(true, api.serves(WarehouseFeature.Lyrics))
        assertNull(warehouse.takeRequest().url.query)
        warehouse.reply(OFF, 404)
        assertEquals(false, api.serves(WarehouseFeature.Lyrics))
    }

    @Test fun `lyrics come through the warehouse when it serves them`() = runTest {
        warehouse.reply(LYRICS)
        val source = lyrics(services())
        assertEquals(LyricsEndpoint.Via.Warehouse, source.via())
        assertTrue(source.lyrics(URI) is LyricsResult.Found)
        assertEquals(0, spicy.requestCount)
        // Kept on the phone like a direct answer.
        assertTrue(source.lyrics(URI) is LyricsResult.Found)
        assertEquals(1, warehouse.requestCount)
    }

    @Test fun `a warehouse without lyrics hands over to the built-in key, and isn't asked again`() = runTest {
        warehouse.reply(OFF, 404)
        spicy.reply(LYRICS)
        val w = services()
        val source = lyrics(w)
        assertTrue(source.lyrics(URI) is LyricsResult.Found)
        assertEquals(1, warehouse.requestCount)
        assertEquals(1, spicy.requestCount)
        assertEquals(LyricsEndpoint.Via.Direct, source.via())

        spicy.reply(LYRICS)
        assertTrue(source.lyrics("spotify:track:1aB1cD2eF3gH4iJ5kL6mN7") is LyricsResult.Found)
        assertEquals(1, warehouse.requestCount)
    }

    @Test fun `an unreachable warehouse hands over to the built-in key`() = runTest {
        spicy.reply(LYRICS)
        val w = services()
        warehouse.close()
        assertTrue(lyrics(w).lyrics(URI) is LyricsResult.Found)
        assertTrue(w.state.value is WarehouseState.Unreachable)
    }

    @Test fun `with neither, lyrics aren't set up and nothing is sent`() = runTest {
        val source = lyrics(services(), direct(key = ""))
        warehouse.reply(OFF, 404)
        // The warehouse is tried until it says it has no lyrics.
        assertEquals(LyricsResult.NotConfigured, source.lyrics(URI))
        assertNull(source.via())
        assertEquals(LyricsResult.NotConfigured, source.lyrics(URI))
        assertEquals(1, warehouse.requestCount)
        assertEquals(0, spicy.requestCount)
    }

    @Test fun `the warehouse's age counts toward the 30 days`() = runTest {
        // Kept by the warehouse for 7 days already.
        warehouse.reply(LYRICS, headers = mapOf("Age" to (7 * 86_400).toString()))
        val source = lyrics(services())
        assertTrue(source.lyrics(URI) is LyricsResult.Found)
        now += LyricsCache.MAX_AGE_MS - 7 * 86_400_000L
        warehouse.reply(LYRICS)
        assertTrue(source.lyrics(URI) is LyricsResult.Found)
        assertEquals(2, warehouse.requestCount)
    }
}
