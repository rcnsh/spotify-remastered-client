package app.remastered.spotify.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.remastered.spotify.web.await
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A token endpoint failure. [status] is null for malformed responses; network errors stay [IOException]. */
class AuthException(
    message: String,
    val status: Int? = null,
    val error: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isInvalidGrant get() = error == "invalid_grant"
}

/**
 * Spotify's accounts token endpoint: code exchange (PKCE) and refresh. Throws [AuthException] for
 * error responses and [IOException] for network failures. Never logs tokens, codes or the secret.
 */
class TokenClient(
    private val http: OkHttpClient,
    private val clientId: String,
    private val redirectUri: String,
    clientSecret: String? = null,
    tokenUrl: String = "https://accounts.spotify.com/api/token",
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val secret = clientSecret?.takeIf { it.isNotBlank() }
    private val url: HttpUrl = tokenUrl.toHttpUrl()

    suspend fun exchange(code: String, verifier: String): Tokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
        return try {
            post(form.add("client_id", clientId).add("code_verifier", verifier).build(), basic = false, previous = null)
        } catch (e: AuthException) {
            // When the Spotify app (not the browser) handles login it drops our code_challenge, so
            // the code isn't a PKCE code: Spotify rejects the verifier, or (seen on 9.1.80) answers
            // `invalid_request: Invalid client secret` because it wants one. Apps with a client
            // secret can still finish the authorization-code flow without PKCE.
            val message = e.message.orEmpty()
            val verifierRejected = e.isInvalidGrant && message.contains("verifier", ignoreCase = true)
            val secretRequired = e.error == "invalid_request" && message.contains("client secret", ignoreCase = true)
            if (e.status != 400 || !(verifierRejected || secretRequired) || secret == null) throw e
            post(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirectUri)
                    .build(),
                basic = true,
                previous = null,
            )
        }
    }

    suspend fun refresh(tokens: Tokens): Tokens {
        val basic = tokens.usesClientSecret && secret != null
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
        if (!basic) form.add("client_id", clientId)
        return post(form.build(), basic, previous = tokens)
    }

    private suspend fun post(form: FormBody, basic: Boolean, previous: Tokens?): Tokens = withContext(io) {
        val request = Request.Builder().url(url).post(form).apply {
            if (basic) header("Authorization", Credentials.basic(clientId, secret!!))
        }.build()
        val (code, body) = http.newCall(request).await().use { it.code to it.body.string() }
        if (code !in 200..299) {
            val err = runCatching { json.decodeFromString<ErrorDto>(body) }.getOrNull()
            throw AuthException(
                "Spotify token endpoint $code: ${err?.error ?: "error"}${err?.description?.let { " ($it)" }.orEmpty()}",
                code,
                err?.error,
            )
        }
        val dto = try {
            json.decodeFromString<TokenDto>(body)
        } catch (e: Exception) {
            throw AuthException("Malformed token response", null, null, e)
        }
        if (dto.accessToken.isEmpty()) throw AuthException("Token response had no access token")
        Tokens(
            accessToken = dto.accessToken,
            // Spotify may omit the refresh token on refresh; the old one stays valid then.
            refreshToken = dto.refreshToken ?: previous?.refreshToken
                ?: throw AuthException("Token response had no refresh token"),
            expiresAtEpochMs = clock() + dto.expiresIn * 1000,
            scope = dto.scope ?: previous?.scope.orEmpty(),
            usesClientSecret = basic,
        )
    }

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        val scope: String? = null,
    )

    @Serializable
    private data class ErrorDto(
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
