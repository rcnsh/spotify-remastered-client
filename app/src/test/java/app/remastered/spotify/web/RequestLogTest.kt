package app.remastered.spotify.web

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RequestLogTest {
    private val server = MockWebServer()
    private val log = RequestLog(max = 3)
    private val http = OkHttpClient.Builder().addInterceptor(log).build()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun get(path: String) = http.newCall(
        Request.Builder().url(server.url(path)).header("Authorization", "Bearer secret-token").build(),
    ).execute().close()

    @Test fun recordsMethodPathQueryAndStatusNewestFirst() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.enqueue(MockResponse.Builder().code(429).body("").build())
        get("/v1/me")
        get("/v1/search?q=abc&type=track")

        val (newest, oldest) = log.entries.value
        assertEquals("/v1/search", newest.path)
        assertEquals("q=abc&type=track", newest.query)
        assertEquals(429, newest.status)
        assertFalse(newest.ok)
        assertEquals("GET", oldest.method)
        assertEquals("/v1/me", oldest.path)
        assertNull(oldest.query)
        assertTrue(oldest.ok)
    }

    @Test fun neverKeepsHeaders() {
        server.enqueue(MockResponse.Builder().code(200).build())
        get("/v1/me")
        assertFalse(log.entries.value.single().toString().contains("secret-token"))
    }

    @Test fun keepsAtMostMax() {
        repeat(5) { server.enqueue(MockResponse.Builder().code(200).build()) }
        repeat(5) { get("/v1/p$it") }
        assertEquals(listOf("/v1/p4", "/v1/p3", "/v1/p2"), log.entries.value.map { it.path })
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }

    @Test fun recordsNetworkFailures() {
        val url = server.url("/v1/me")
        server.close()
        try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (_: IOException) {
        }
        val e = log.entries.value.single()
        assertNull(e.status)
        assertFalse(e.ok)
        assertTrue(e.error!!.isNotEmpty())
    }
}
