package app.remastered.warehouse

import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.WarehouseState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WarehouseServicesTest {
    private val server = MockWebServer()
    /** Path → response code; anything not listed answers 200 with an empty play list. */
    private val codes = mutableMapOf<String, Int>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val code = codes[request.url.encodedPath] ?: 200
                val body = when {
                    code != 200 -> """{"error":"x"}"""
                    request.url.encodedPath == "/auth/token" -> """{"id":"tok_1","token":"minted","access":"read","expires_ms":1}"""
                    else -> """{"plays":[],"ranges":[]}"""
                }
                return MockResponse.Builder().code(code).body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val config get() = WarehouseConfig(server.url("/").toString(), "t")

    private fun services(store: WarehouseConfigStore = InMemoryWarehouseConfigStore(), defaults: WarehouseConfig? = null) =
        WarehouseServices(store, OkHttpClient(), defaults, Dispatchers.Unconfined, sleep = {})

    @Test fun `not configured hides everything and sends nothing`() = runTest {
        val w = services()
        assertEquals(WarehouseState.NotConfigured, w.state.value)
        assertFalse(w.state.value.isUsable)
        assertEquals(Problem.NotConfigured, (w.check().exceptionOrNull() as app.remastered.core.warehouse.WarehouseException).problem)
        assertEquals(0, server.requestCount)
    }

    @Test fun `check confirms the config and probes features`() = runTest {
        codes["/api/track"] = 400
        codes["/api/artist"] = 400
        codes["/api/on-this-day"] = 400
        val w = services(InMemoryWarehouseConfigStore(config))
        assertEquals(WarehouseState.Checking, w.state.value)
        assertTrue(w.state.value.isUsable)
        assertTrue(w.check().isSuccess)
        assertEquals(WarehouseState.Ready, w.state.value)
        assertTrue(w.missing.value.isEmpty())
        assertTrue(w.supports(WarehouseFeature.WrappedRanges))
        assertEquals(1 + WarehouseFeature.entries.size, server.requestCount)
    }

    @Test fun `an older warehouse misses the new routes`() = runTest {
        WarehouseFeature.entries.forEach { codes["/" + it.path] = 404 }
        val w = services(InMemoryWarehouseConfigStore(config))
        w.check()
        assertEquals(WarehouseState.Ready, w.state.value)
        assertEquals(WarehouseFeature.entries.toSet(), w.missing.value)
        assertFalse(w.supports(WarehouseFeature.OnThisDay))
        // Once deployed, a successful call brings the feature back.
        codes.remove("/api/wrapped/ranges")
        w.api.wrappedRanges()
        assertFalse(WarehouseFeature.WrappedRanges in w.missing.value)
    }

    @Test fun `rejected token`() = runTest {
        codes["/api/plays"] = 401
        val w = services(InMemoryWarehouseConfigStore(config))
        assertTrue(w.check().isFailure)
        assertEquals(WarehouseState.Unauthorized, w.state.value)
        assertFalse(w.state.value.isUsable)
        assertEquals(1, server.requestCount)
    }

    @Test fun `unreachable warehouse stays usable`() = runTest {
        val w = services(InMemoryWarehouseConfigStore(config))
        server.close()
        w.check()
        assertTrue(w.state.value is WarehouseState.Unreachable)
        assertTrue(w.state.value.isUsable)
    }

    @Test fun `a broken stat doesn't hide a working warehouse`() = runTest {
        val w = services(InMemoryWarehouseConfigStore(config))
        w.check()
        codes["/api/stats/rhythm"] = 500
        runCatching { w.api.rhythm() }
        assertEquals(WarehouseState.Ready, w.state.value)
    }

    @Test fun `debug defaults apply until the user saves or clears`() = runTest {
        val store = InMemoryWarehouseConfigStore()
        val seeded = services(store, defaults = config)
        assertEquals(config, seeded.config.value)

        seeded.clear()
        assertNull(seeded.config.value)
        assertEquals(WarehouseState.NotConfigured, seeded.state.value)
        assertNull(services(store, defaults = config).config.value)

        val other = WarehouseConfig("https://elsewhere.example/", "u")
        seeded.save(other)
        assertEquals(other, services(store, defaults = config).config.value)
    }

    @Test fun `test checks a config without saving it`() = runTest {
        val w = services()
        assertTrue(w.test(config).isSuccess)
        assertNull(w.config.value)
        assertEquals(WarehouseState.NotConfigured, w.state.value)
        codes["/api/plays"] = 401
        assertEquals(Problem.Unauthorized, (w.test(config).exceptionOrNull() as app.remastered.core.warehouse.WarehouseException).problem)
    }

    @Test fun `signing in saves the minted token like a typed one, then checks it`() = runTest {
        val store = InMemoryWarehouseConfigStore()
        val w = services(store)
        val page = w.startSignIn(config.baseUrl, "Android (Test)")!!
        assertTrue(page, "/auth/login?" in page)
        assertEquals("Waiting for the browser…", w.signInStatus.value)

        assertTrue(w.finishSignIn("app.remastered://warehouse-auth?code=c").isSuccess)
        val saved = WarehouseConfig(config.baseUrl, "minted")
        assertEquals(saved, w.config.value)
        assertEquals(saved, store.load())
        assertEquals(WarehouseState.Ready, w.state.value)
        assertTrue(w.signInStatus.value!!.startsWith("Signed in"))
        assertEquals("/auth/token", server.takeRequest().url.encodedPath)
        assertEquals("Bearer minted", server.takeRequest().headers["Authorization"])
    }

    @Test fun `a failed sign-in keeps the old config and says why`() = runTest {
        val w = services(InMemoryWarehouseConfigStore(config))
        w.startSignIn(config.baseUrl, "d")
        assertTrue(w.finishSignIn("app.remastered://warehouse-auth?error=access_denied").isFailure)
        assertEquals(config, w.config.value)
        assertEquals("Sign-in cancelled.", w.signInStatus.value)
        assertEquals(0, server.requestCount)
        assertNull(w.startSignIn("http://music-api.example.com", "d"))
        assertTrue(w.signInStatus.value!!.contains("address"))
    }

    @Test fun `defaults need a usable url and a token`() {
        assertNull(WarehouseServices.defaults("", "t"))
        assertNull(WarehouseServices.defaults("https://w.example", " "))
        assertEquals(WarehouseConfig("https://w.example/", "t"), WarehouseServices.defaults("https://w.example", "t"))
    }

    @Test fun `urls are normalized`() {
        assertEquals("https://music-api.example.com/", WarehouseConfig.normalizeUrl(" music-api.example.com "))
        assertEquals("https://music-api.example.com/", WarehouseConfig.normalizeUrl("https://music-api.example.com/?x=1#y"))
        assertEquals("https://w.example/prefix/", WarehouseConfig.normalizeUrl("https://w.example/prefix"))
        assertEquals("http://10.0.2.2:8787/", WarehouseConfig.normalizeUrl("http://10.0.2.2:8787"))
        assertNull(WarehouseConfig.normalizeUrl("http://music-api.example.com"))
        assertNull(WarehouseConfig.normalizeUrl(""))
        assertNull(WarehouseConfig.normalizeUrl("not a url at all"))
    }
}
