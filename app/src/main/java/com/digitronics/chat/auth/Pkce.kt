package com.digitronics.chat.auth

import com.digitronics.chat.util.Base64Url
import com.digitronics.chat.util.sha256
import java.security.SecureRandom

/**
 * RFC 7636 PKCE with S256. The verifier is 32 random bytes (256 bits) in
 * base64url, which is exactly 43 characters — inside the server's
 * `^[A-Za-z0-9._~-]{43,128}$` rule. The state is generated the same way.
 */
object Pkce {
    private const val RANDOM_BYTES = 32

    fun newVerifier(random: SecureRandom = SecureRandom()): String = randomToken(random)

    fun newState(random: SecureRandom = SecureRandom()): String = randomToken(random)

    /** BASE64URL(SHA256(ASCII(verifier))), as the Worker's `verifyPkceS256` recomputes it. */
    fun challengeS256(verifier: String): String {
        require(VERIFIER_SHAPE.matches(verifier)) { "Not an RFC 7636 code verifier." }
        return Base64Url.encode(sha256(verifier.toByteArray(Charsets.US_ASCII)))
    }

    private fun randomToken(random: SecureRandom): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    private val VERIFIER_SHAPE = Regex("^[A-Za-z0-9._~-]{43,128}$")
}
