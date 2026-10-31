package app.remastered.spotify.auth

import java.security.SecureRandom
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

class SpotifyAuthTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun auth() = SpotifyAuth(
        clientId = "cid",
        redirectUri = "app.remastered://callback",
        store = store,
        tokenClient = TokenClient(OkHttpClient(), "cid", "app.remastered://callback", null, server.url("/api/token").toString()),
        scopes = listOf("user-top-read", "streaming"),
        random = SecureRandom(),
    )

    private fun enqueueTokens() = server.enqueue(
        MockResponse.Builder().body("""{"access_token":"A","expires_in":3600,"refresh_token":"R","scope":"streaming"}""").build(),
    )

    @Test fun `login url asks for a pkce code and keeps the verifier`() {
        val url = auth().loginUrl()
        val pending = store.pendingLogin()!!
        assertEquals("accounts.spotify.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        assertEquals("cid", url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("app.remastered://callback", url.queryParameter("redirect_uri"))
        assertEquals("user-top-read streaming", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(Pkce.challenge(pending.verifier), url.queryParameter("code_challenge"))
        assertEquals(pending.state, url.queryParameter("state"))
    }

    @Test fun `redirect is the callback host only`() {
        val auth = auth()
        assertTrue(auth.isRedirect("app.remastered://callback?code=c&state=s"))
        assertTrue(auth.isRedirect("APP.REMASTERED://callback/?error=access_denied"))
        assertFalse(auth.isRedirect("app.remastered://warehouse-auth?code=c"))
        assertFalse(auth.isRedirect("https://callback?code=c"))
        assertFalse(auth.isRedirect(null))
    }

    @Test fun `redirect with code trades it with the verifier and saves the tokens`() = runTest {
        val auth = auth()
        auth.loginUrl()
        val pending = store.pendingLogin()!!
        enqueueTokens()
        val tokens = auth.handleRedirect("app.remastered://callback?code=c%2F1&state=${pending.state}").getOrThrow()
        assertEquals("A", tokens.accessToken)
        assertFalse(tokens.usesClientSecret)
        assertEquals(tokens, store.load())
        assertNull(store.pendingLogin())
        val form = server.takeRequest().body!!.utf8()
        assertTrue(form, "code=c%2F1" in form)
        assertTrue(form, "code_verifier=${pending.verifier}" in form)
    }

    @Test fun `state mismatch is rejected without a token request`() = runTest {
        val auth = auth()
        auth.loginUrl()
        val e = auth.handleRedirect("app.remastered://callback?code=c&state=nope").exceptionOrNull() as AuthException
        assertEquals("Login state mismatch", e.message)
        assertEquals(0, server.requestCount)
        assertNull(store.load())
        assertNull(store.pendingLogin())
    }

    @Test fun `denied login reads as cancelled`() = runTest {
        val auth = auth()
        auth.loginUrl()
        val e = auth.handleRedirect("app.remastered://callback?error=access_denied&state=x").exceptionOrNull() as AuthException
        assertEquals("cancelled", e.error)
        assertNull(store.pendingLogin())
    }

    @Test fun `code without a login in progress is rejected`() = runTest {
        val e = auth().handleRedirect("app.remastered://callback?code=c&state=s").exceptionOrNull() as AuthException
        assertEquals("No login in progress", e.message)
        assertEquals(0, server.requestCount)
    }
}
