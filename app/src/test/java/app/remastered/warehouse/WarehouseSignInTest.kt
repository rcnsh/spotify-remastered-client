package app.remastered.warehouse

import app.remastered.spotify.auth.Pkce
import app.remastered.warehouse.WarehouseSignIn.Redirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

// The exchange's response follows music-warehouse's POST /auth/token (src/signin.ts, mintToken in
// src/auth.ts) with a made-up id and token.
private const val MINTED = """{"id":"tok_abc123","token":"mw_minted_token","name":"Android (Pixel)","access":"read","expires_ms":1800000000000}"""

class WarehouseSignInTest {
    private val server = MockWebServer()
    private val store = InMemoryPendingSignInStore()
    private var now = 1_000_000L
    private lateinit var signIn: WarehouseSignIn
    private val base get() = server.url("/").toString()

    @Before fun setUp() {
        server.start()
        signIn = WarehouseSignIn(OkHttpClient(), store, Dispatchers.Unconfined, clock = { now })
    }

    @After fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    // --- start ---

    @Test fun `start keeps the verifier and sends its S256 challenge`() {
        val url = signIn.start("https://music-api.example.com/", "Android (Pixel 10 Pro)")
        val pending = store.load()!!
        assertEquals("https://music-api.example.com/", pending.baseUrl)
        assertEquals(now, pending.startedMs)
        assertTrue(pending.verifier.length in 43..128)
        assertTrue(pending.verifier.all { it.isLetterOrDigit() || it in "-._~" })

        assertEquals("/auth/login", url.encodedPath)
        assertEquals("Android (Pixel 10 Pro)", url.queryParameter("name"))
        assertEquals("app.remastered://warehouse-auth", url.queryParameter("return_to"))
        assertEquals(Pkce.challenge(pending.verifier), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertTrue(signIn.inProgress)
    }

    @Test fun `login url encodes its parameters`() {
        val url = WarehouseSignIn.loginUrl("https://w.example/base/", "Android (A&B=C)", "abc").toString()
        assertTrue(url, url.startsWith("https://w.example/base/auth/login?"))
        assertTrue(url, "return_to=app.remastered%3A%2F%2Fwarehouse-auth" in url)
        assertTrue(url, "name=Android%20%28A%26B%3DC%29&" in url)
        assertFalse(url, "access" in url)
    }

    @Test fun `device name is capped at 64 characters`() {
        assertEquals("Android (Pixel 10 Pro)", WarehouseSignIn.deviceName("Pixel 10 Pro"))
        assertEquals("Android (phone)", WarehouseSignIn.deviceName("  "))
        assertEquals(64, WarehouseSignIn.deviceName("x".repeat(100)).length)
        assertEquals(64, WarehouseSignIn.loginUrl(base, "y".repeat(100), "c").queryParameter("name")!!.length)
    }

    // --- redirect ---

    @Test fun `parses codes and errors, and only its own host`() {
        assertEquals(Redirect.Code("a b+c"), WarehouseSignIn.parseRedirect("app.remastered://warehouse-auth?code=a%20b%2Bc"))
        assertEquals(Redirect.Error("not_owner"), WarehouseSignIn.parseRedirect("app.remastered://warehouse-auth?error=not_owner"))
        assertEquals(Redirect.Error("missing_code"), WarehouseSignIn.parseRedirect("app.remastered://warehouse-auth"))
        // The Web API login's redirect belongs to Spotify's auth AAR.
        assertEquals(Redirect.NotOurs, WarehouseSignIn.parseRedirect("app.remastered://callback?code=x"))
        assertEquals(Redirect.NotOurs, WarehouseSignIn.parseRedirect("https://warehouse-auth/?code=x"))
        assertEquals(Redirect.NotOurs, WarehouseSignIn.parseRedirect("not a uri"))
        assertFalse(WarehouseSignIn.isRedirect(null))
        assertTrue(WarehouseSignIn.isRedirect("app.remastered://warehouse-auth?code=x"))
        assertFalse(Redirect.Code("secret").toString().contains("secret"))
    }

    @Test fun `errors read as sentences`() {
        assertEquals("Sign-in cancelled.", WarehouseSignIn.errorMessage("access_denied"))
        assertTrue("doesn't own" in WarehouseSignIn.errorMessage("not_owner"))
        assertTrue("right now" in WarehouseSignIn.errorMessage("unavailable"))
        assertTrue("(weird)" in WarehouseSignIn.errorMessage("weird"))
    }

    // --- exchange ---

    @Test fun `exchanges the code and verifier for a read token`() = runTest {
        signIn.start(base, "Android (Pixel)")
        val verifier = store.load()!!.verifier
        enqueue(MINTED)
        val cfg = signIn.complete("app.remastered://warehouse-auth?code=one-time").getOrThrow()
        assertEquals(WarehouseConfig(base, "mw_minted_token"), cfg)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/auth/token", req.url.encodedPath)
        assertNull(req.headers["Origin"])
        assertNull(req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/json"))
        val sent = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("one-time", sent["code"]!!.jsonPrimitive.content)
        assertEquals(verifier, sent["code_verifier"]!!.jsonPrimitive.content)
        // Used up.
        assertNull(store.load())
        assertFalse(signIn.inProgress)
    }

    @Test fun `exchange goes to the warehouse the sign-in started with`() = runTest {
        signIn.start(server.url("/sub/").toString(), "d")
        enqueue(MINTED)
        signIn.complete("app.remastered://warehouse-auth?code=c").getOrThrow()
        assertEquals("/sub/auth/token", server.takeRequest().url.encodedPath)
    }

    @Test fun `invalid_grant says to try again and is not retried`() = runTest {
        signIn.start(base, "d")
        enqueue("""{"error":"invalid_grant"}""", 400)
        val e = signIn.complete("app.remastered://warehouse-auth?code=c").exceptionOrNull()
        assertTrue(e is SignInException)
        assertTrue(e!!.message!!, "expired or was already used" in e.message!!)
        assertEquals(1, server.requestCount)
        assertNull(store.load())
    }

    @Test fun `an error redirect sends nothing`() = runTest {
        signIn.start(base, "d")
        val e = signIn.complete("app.remastered://warehouse-auth?error=not_owner").exceptionOrNull()
        assertEquals(WarehouseSignIn.errorMessage("not_owner"), e!!.message)
        assertEquals(0, server.requestCount)
        assertNull(store.load())
    }

    @Test fun `a code with no sign-in in progress, or a stale one, is refused`() = runTest {
        assertTrue(signIn.complete("app.remastered://warehouse-auth?code=c").exceptionOrNull() is SignInException)
        signIn.start(base, "d")
        now += WarehouseSignIn.PENDING_TTL_MS + 1
        assertFalse(signIn.inProgress)
        assertTrue(signIn.complete("app.remastered://warehouse-auth?code=c").exceptionOrNull() is SignInException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `refuses anything but a read token, and odd answers`() = runTest {
        for ((body, code) in listOf(
            """{"token":"t","access":"admin"}""" to 200,
            """{"id":"x"}""" to 200,
            "<html>blocked</html>" to 200,
            "<html>blocked</html>" to 403,
            """{"error":"Not found"}""" to 404,
        )) {
            signIn.start(base, "d")
            enqueue(body, code)
            val e = signIn.complete("app.remastered://warehouse-auth?code=c").exceptionOrNull()
            assertTrue("$code $body", e is SignInException)
        }
        // A token with no access field (an older shape) is fine.
        signIn.start(base, "d")
        enqueue("""{"token":"t"}""")
        assertEquals("t", signIn.complete("app.remastered://warehouse-auth?code=c").getOrThrow().token)
    }

    @Test fun `unreachable warehouse is a sign-in error`() = runTest {
        signIn.start("http://127.0.0.1:1/", "d")
        val e = signIn.complete("app.remastered://warehouse-auth?code=c").exceptionOrNull()
        assertTrue(e is SignInException)
        assertTrue(e!!.message!!, "Can't reach" in e.message!!)
    }

    @Test fun `pending sign-in hides the verifier`() {
        assertFalse(PendingSignIn("https://w/", "secret-verifier", 0).toString().contains("secret-verifier"))
    }
}
