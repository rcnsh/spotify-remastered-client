package app.remastered.spotify.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) with the S256 method. */
object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes as base64url: 86 chars, all from the unreserved set the RFC requires. */
    fun verifier(random: SecureRandom = SecureRandom()): String =
        b64.encodeToString(ByteArray(64).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
