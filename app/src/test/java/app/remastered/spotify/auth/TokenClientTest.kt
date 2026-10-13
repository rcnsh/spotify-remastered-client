package app.remastered.spotify.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TokenClientTest {
    private val server = MockWebServer()
    private val now = 1_000_000L

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client(secret: String? = null) =
        TokenClient(OkHttpClient(), "cid", "app://cb", secret, server.url("/api/token").toString(), clock = { now })

    private fun enqueue(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun RecordedRequest.form(): Map<String, String> =
        body!!.utf8().split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test fun `exchange sends pkce form`() = runTest {
        enqueue("""{"access_token":"A","token_type":"Bearer","scope":"user-top-read","expires_in":3600,"refresh_token":"R"}""")
        val t = client().exchange("code1", "ver1")
        assertEquals(Tokens("A", "R", now + 3_600_000, "user-top-read", usesClientSecret = false), t)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertNull(req.headers["Authorization"])
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code", "code" to "code1", "redirect_uri" to "app://cb",
                "client_id" to "cid", "code_verifier" to "ver1",
            ),
            req.form(),
        )
    }

    @Test fun `rejected verifier falls back to client secret`() = runTest {
        enqueue("""{"error":"invalid_grant","error_description":"code_verifier was incorrect"}""", 400)
        enqueue("""{"access_token":"A","expires_in":3600,"refresh_token":"R","scope":"s"}""")
        val t = client(secret = "shh").exchange("code1", "ver1")
        assertTrue(t.usesClientSecret)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals(Credentials.basic("cid", "shh"), retry.headers["Authorization"])
        val form = retry.form()
        assertFalse("code_verifier" in form)
        assertEquals("code1", form["code"])
    }

    @Test fun `code without pkce falls back to client secret`() = runTest {
        // The Spotify app's login drops code_challenge; Spotify then asks for the secret.
        enqueue("""{"error":"invalid_request","error_description":"Invalid client secret"}""", 400)
        enqueue("""{"access_token":"A","expires_in":3600,"refresh_token":"R","scope":"s"}""")
        val t = client(secret = "shh").exchange("code1", "ver1")
        assertTrue(t.usesClientSecret)
        assertFalse("client_secret" in server.takeRequest().form())
        val retry = server.takeRequest()
        assertEquals(Credentials.basic("cid", "shh"), retry.headers["Authorization"])
        assertFalse("code_verifier" in retry.form())
    }

    @Test fun `rejected verifier without secret throws`() = runTest {
        enqueue("""{"error":"invalid_grant","error_description":"code_verifier was incorrect"}""", 400)
        try {
            client().exchange("code1", "ver1")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(400, e.status)
            assertTrue(e.isInvalidGrant)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `other invalid_grant does not fall back`() = runTest {
        enqueue("""{"error":"invalid_grant","error_description":"Invalid authorization code"}""", 400)
        try {
            client(secret = "shh").exchange("code1", "ver1")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("invalid_grant", e.error)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `refresh keeps old refresh token when absent`() = runTest {
        enqueue("""{"access_token":"A2","token_type":"Bearer","expires_in":3600,"scope":"s"}""")
        val t = client().refresh(Tokens("A1", "R1", 0, "s"))
        assertEquals("A2", t.accessToken)
        assertEquals("R1", t.refreshToken)
        val form = server.takeRequest().form()
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "R1", "client_id" to "cid"), form)
    }

    @Test fun `refresh uses new refresh token and basic auth when exchange did`() = runTest {
        enqueue("""{"access_token":"A2","expires_in":3600,"refresh_token":"R2"}""")
        val t = client(secret = "shh").refresh(Tokens("A1", "R1", 0, "s", usesClientSecret = true))
        assertEquals("R2", t.refreshToken)
        assertEquals("s", t.scope)
        assertTrue(t.usesClientSecret)
        val req = server.takeRequest()
        assertEquals(Credentials.basic("cid", "shh"), req.headers["Authorization"])
        assertFalse("client_id" in req.form())
    }

    @Test fun `provider refreshes when expiring and is single flight`() = runTest {
        enqueue("""{"access_token":"A2","expires_in":3600}""")
        val store = InMemoryTokenStore(Tokens("A1", "R1", now + 30_000, "s"))
        val provider = StoredTokenProvider(store, client(), clock = { now })
        val got = List(5) { async { provider.accessToken() } }.awaitAll()
        assertEquals(List(5) { "A2" }, got)
        assertEquals(1, server.requestCount)
        assertEquals("R1", store.load()!!.refreshToken)
    }

    @Test fun `provider returns valid token without refreshing`() = runTest {
        val provider = StoredTokenProvider(InMemoryTokenStore(Tokens("A1", "R1", now + 120_000, "s")), client(), clock = { now })
        assertEquals("A1", provider.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test fun `revoked refresh token signs out`() = runTest {
        enqueue("""{"error":"invalid_grant","error_description":"Refresh token revoked"}""", 400)
        val store = InMemoryTokenStore(Tokens("A1", "R1", 0, "s"))
        val provider = StoredTokenProvider(store, client(), clock = { now })
        try {
            provider.refresh()
            fail("expected AuthException")
        } catch (_: AuthException) {
        }
        assertNull(store.load())
    }

    @Test fun `tokens toString hides secrets`() {
        val s = Tokens("ACCESS", "REFRESH", 1, "scope").toString()
        assertFalse(s.contains("ACCESS") || s.contains("REFRESH"))
    }
}
