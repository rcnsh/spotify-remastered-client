package app.remastered.spicylyrics

import app.remastered.core.lyrics.Contributor
import app.remastered.core.lyrics.Lyrics
import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsResult.Problem
import app.remastered.core.lyrics.Provider
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

// Fixture lyrics are made up; the shapes follow developers.spicylyrics.org/docs.
private const val ID = "1QV6tiMFM6fSOKOGLMHYYg"
private const val URI = "spotify:track:$ID"

private val SYLLABLE = """
{"Body":{"Type":"Syllable","SongWriters":["Ada Writer"," "],"StartTime":1.5,"EndTime":9.0,
 "Content":[
  {"Type":"Vocal","OppositeAligned":false,
   "Lead":{"Syllables":[{"Text":"Hel","StartTime":1.5,"EndTime":1.8,"IsPartOfWord":true},{"Text":"lo","StartTime":1.8,"EndTime":2.1},
     {"Text":"world","StartTime":2.1,"EndTime":2.9}],"StartTime":1.5,"EndTime":2.9,"TranslatedText":"Hola mundo"},
   "Background":[{"Syllables":[{"Text":"(world)","StartTime":3.0,"EndTime":3.4}],"StartTime":3.0,"EndTime":3.4}]},
  {"Type":"Vocal","OppositeAligned":true,
   "Lead":{"Syllables":[{"Text":"こん","StartTime":5.0,"EndTime":5.5,"IsPartOfWord":true,"TransliteratedText":"kon"},
     {"Text":"にちは","StartTime":5.5,"EndTime":6.2,"TransliteratedText":"nichiwa"}],"StartTime":5.0,"EndTime":6.2}}
 ],
 "id":"$ID","source":"spicy_lyrics","SomethingNew":true,
 "UploadAttribution":{"Uploader":{"id":"1","username":"uploader1","url":"https://spicylyrics.org/uid/1","avatar":"https://cdn/1.webp"},
   "Maker":{"id":"2","username":"maker2","url":"http://insecure.example/2"}}},
 "Status":200,"Type":"object"}
""".trimIndent()

private val LINE = """
{"Body":{"Type":"Line","StartTime":10.81,"EndTime":20,"Content":[
  {"Type":"Vocal","Text":"Second line","StartTime":14.0,"EndTime":0,"OppositeAligned":false},
  {"Type":"Vocal","Text":"First line (echo)","StartTime":10.81,"EndTime":13.5,"OppositeAligned":false,"TransliteratedText":"first"},
  {"Type":"Vocal","Text":"   ","StartTime":15,"EndTime":16}],
 "id":"$ID","source":"spotify","UploadAttribution":{"Uploader":{"username":"not-shown"}}},"Status":200,"Type":"object"}
""".trimIndent()

private val STATIC = """
{"Body":{"Type":"Static","Lines":[{"Text":"Plain one"},{"Text":"Plain two","TranslatedText":"Dos"},{"Text":""}],
 "id":"$ID","source":"apple_music"},"Status":200,"Type":"object"}
""".trimIndent()

class ParseTest {
    @Test fun `syllable lyrics keep words, background vocals, sides and credits`() {
        val l = parseLyrics(SYLLABLE, ID)!!
        assertEquals(Lyrics.Sync.Syllable, l.sync)
        assertEquals(2, l.lines.size)
        val first = l.lines[0]
        assertEquals("Hello world", first.lead.text)
        assertEquals(1_500L, first.startMs)
        assertEquals(3_400L, first.endMs) // background ends after the lead
        assertEquals(listOf("(world)"), first.background.map { it.text })
        assertEquals("Hola mundo", first.translation)
        assertFalse(first.oppositeAligned)
        val second = l.lines[1]
        assertTrue(second.oppositeAligned)
        assertEquals("こんにちは", second.lead.text)
        assertEquals("konnichiwa", second.lead.transliteration)
        assertEquals(listOf("Ada Writer"), l.songWriters)
        assertEquals(Provider.SpicyLyrics, l.attribution.provider)
        assertEquals(Contributor("uploader1", "https://spicylyrics.org/uid/1"), l.attribution.uploader)
        // Only https links are kept.
        assertEquals(Contributor("maker2", null), l.attribution.maker)
    }

    @Test fun `line lyrics are sorted, blank lines dropped, open ends closed`() {
        val l = parseLyrics(LINE, ID)!!
        assertEquals(Lyrics.Sync.Line, l.sync)
        assertEquals(listOf("First line (echo)", "Second line"), l.lines.map { it.lead.text })
        assertEquals(10_810L, l.lines[0].startMs)
        assertEquals("first", l.lines[0].lead.transliteration)
        assertEquals(14_000L + 5_000, l.lines[1].endMs)
        // Commercial source: contributors in the response are ignored.
        assertEquals(Provider.Spotify, l.attribution.provider)
        assertNull(l.attribution.uploader)
    }

    @Test fun `static lyrics have no timing`() {
        val l = parseLyrics(STATIC, ID)!!
        assertEquals(Lyrics.Sync.Static, l.sync)
        assertEquals(listOf("Plain one", "Plain two"), l.lines.map { it.lead.text })
        assertEquals("Dos", l.lines[1].translation)
        assertEquals(Provider.AppleMusic, l.attribution.provider)
    }

    @Test fun `unknown source is reported as unknown`() =
        assertEquals(Provider.Unknown, parseLyrics(LINE.replace("\"spotify\"", "\"new_source\""), ID)!!.attribution.provider)

    @Test fun `anything else is not lyrics`() {
        assertNull(parseLyrics("not json", ID))
        assertNull(parseLyrics("""{"Body":{"Type":"Karaoke","Content":[]}}""", ID))
        assertNull(parseLyrics("""{"Body":{"Type":"Line","Content":[]}}""", ID))
        assertNull(parseLyrics("""{"Body":{"error":"lyrics_not_found","message":"No lyrics"},"Status":404}""", ID))
    }
}

class SpicyLyricsTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private var now = 1_800_000_000_000L
    private lateinit var cache: LyricsCache

    @Before fun setUp() {
        server.start()
        dir = Files.createTempDirectory("lyrics").toFile()
        cache = LyricsCache(dir) { now }
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun api(key: String = "sl_pk_test") = SpicyLyricsApi(OkHttpClient(), key, server.url("/").toString(), Dispatchers.Unconfined)
    private fun source(key: String = "sl_pk_test") = SpicyLyrics(api(key), cache, { now }, Dispatchers.Unconfined)

    private fun enqueue(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    @Test fun `asks for the track with the key and no origin`() = runTest {
        enqueue(SYLLABLE)
        val r = source().lyrics(URI)
        assertTrue(r is LyricsResult.Found)
        val req = server.takeRequest()
        assertEquals("/v1/lyrics/$ID", req.url.encodedPath)
        assertEquals("Bearer sl_pk_test", req.headers["Authorization"])
        assertNull(req.headers["Origin"])
    }

    @Test fun `second ask is served from the cache`() = runTest {
        enqueue(SYLLABLE)
        val s = source()
        s.lyrics(URI)
        assertTrue(s.lyrics(URI) is LyricsResult.Found)
        assertEquals(1, server.requestCount)
    }

    @Test fun `cached lyrics are refetched after 30 days`() = runTest {
        enqueue(SYLLABLE)
        enqueue(LINE)
        val s = source()
        s.lyrics(URI)
        now += LyricsCache.MAX_AGE_MS
        val r = s.lyrics(URI) as LyricsResult.Found
        assertEquals(Lyrics.Sync.Line, r.lyrics.sync)
        assertEquals(2, server.requestCount)
    }

    @Test fun `no lyrics is remembered for a day`() = runTest {
        enqueue("""{"Body":{"error":"lyrics_not_found"},"Status":404,"Type":"object"}""", 404)
        enqueue(STATIC)
        val s = source()
        assertEquals(LyricsResult.NotFound, s.lyrics(URI))
        assertEquals(LyricsResult.NotFound, s.lyrics(URI))
        assertEquals(1, server.requestCount)
        now += LyricsCache.NOT_FOUND_AGE_MS
        assertTrue(s.lyrics(URI) is LyricsResult.Found)
    }

    @Test fun `rate limit is honoured without asking again`() = runTest {
        enqueue("", 429, mapOf("Retry-After" to "30"))
        val s = source()
        assertEquals(LyricsResult.Failed(Problem.RateLimited, 30_000), s.lyrics(URI))
        now += 10_000
        assertEquals(LyricsResult.Failed(Problem.RateLimited, 20_000), s.lyrics(URI))
        assertEquals(1, server.requestCount)
        now += 20_000
        enqueue(SYLLABLE)
        assertTrue(s.lyrics(URI) is LyricsResult.Found)
    }

    @Test fun `failures map to problems and are not cached`() = runTest {
        val s = source()
        enqueue("", 401); assertEquals(LyricsResult.Failed(Problem.KeyRejected), s.lyrics(URI))
        enqueue("", 403); assertEquals(LyricsResult.Failed(Problem.KeyRejected), s.lyrics(URI))
        enqueue("", 502); assertEquals(LyricsResult.Failed(Problem.ServiceDown), s.lyrics(URI))
        enqueue("<html>", 200); assertEquals(LyricsResult.Failed(Problem.BadResponse), s.lyrics(URI))
        assertNull(cache.get(ID))
    }

    @Test fun `unreachable server is offline`() = runTest {
        val s = source()
        server.close()
        assertEquals(LyricsResult.Failed(Problem.Offline), s.lyrics(URI))
    }

    @Test fun `episodes, local files and keyless builds never hit the network`() = runTest {
        assertEquals(LyricsResult.Unsupported, source().lyrics("spotify:episode:$ID"))
        assertEquals(LyricsResult.Unsupported, source().lyrics("spotify:local:Artist:Album:Song:180"))
        assertEquals(LyricsResult.Unsupported, source().lyrics("spotify:track:../../etc"))
        assertEquals(LyricsResult.NotConfigured, source(key = "").lyrics(URI))
        assertEquals(0, server.requestCount)
    }

    @Test fun `sweep deletes only expired entries`() {
        cache.put(ID, SYLLABLE)
        cache.putNotFound("2WKGGQ6UmyUZGZgRJyEROB")
        now += LyricsCache.NOT_FOUND_AGE_MS
        assertEquals(1, cache.sweep())
        assertEquals(1 to SYLLABLE.toByteArray().size.toLong(), cache.stats())
        now += LyricsCache.MAX_AGE_MS
        assertEquals(1, cache.sweep())
        assertEquals(0 to 0L, cache.stats())
    }
}
