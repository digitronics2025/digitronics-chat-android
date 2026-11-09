package com.digitronics.chat.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun `challenge matches RFC 7636 appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier and state are 256-bit base64url and satisfy the server's shapes`() {
        val verifierShape = Regex("^[A-Za-z0-9._~-]{43,128}$")
        val stateShape = Regex("^[A-Za-z0-9._~-]{16,128}$")
        val challengeShape = Regex("^[A-Za-z0-9_-]{43}$")
        repeat(50) {
            val verifier = Pkce.newVerifier()
            assertEquals(43, verifier.length)
            assertTrue(verifierShape.matches(verifier))
            assertTrue(stateShape.matches(Pkce.newState()))
            assertTrue(challengeShape.matches(Pkce.challengeS256(verifier)))
        }
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a verifier the server would refuse`() {
        Pkce.challengeS256("too-short")
    }

    @Test
    fun `authorize url carries every parameter, encoded`() {
        val url = AuthorizeUrl.build("https://messenger.digitronics.app", "digitronics-chat-android", "CHALLENGE", "STATE")
        assertEquals("/android-auth/authorize", url.encodedPath)
        assertEquals("digitronics-chat-android", url.queryParameter("client_id"))
        assertEquals("https://messenger.digitronics.app/android-auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("CHALLENGE", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("STATE", url.queryParameter("state"))
        assertTrue(url.toString().contains("redirect_uri=https%3A%2F%2Fmessenger.digitronics.app%2Fandroid-auth%2Fcallback"))
    }

    @Test
    fun `only our https callback path counts as a sign-in hand-back`() {
        val base = "https://messenger.digitronics.app"
        assertTrue(AuthorizeUrl.isCallback(base, "https://messenger.digitronics.app/android-auth/callback?code=x&state=y".toHttpUrl()))
        assertFalse(AuthorizeUrl.isCallback(base, "https://evil.example/android-auth/callback?code=x".toHttpUrl()))
        assertFalse(AuthorizeUrl.isCallback(base, "http://messenger.digitronics.app/android-auth/callback".toHttpUrl()))
        assertFalse(AuthorizeUrl.isCallback(base, "https://messenger.digitronics.app/install-android".toHttpUrl()))
    }
}
