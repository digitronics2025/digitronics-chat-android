package com.digitronics.chat.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The consent page the Custom Tab opens, and the single redirect the server accepts. */
object AuthorizeUrl {
    const val AUTHORIZE_PATH = "/android-auth/authorize"
    const val CALLBACK_PATH = "/android-auth/callback"

    fun redirectUri(baseUrl: String): String = baseUrl.trimEnd('/') + CALLBACK_PATH

    fun build(baseUrl: String, clientId: String, codeChallenge: String, state: String): HttpUrl =
        (baseUrl.trimEnd('/') + AUTHORIZE_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri(baseUrl))
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()

    /** True only for `https://<our host>/android-auth/callback` — nothing else is a sign-in hand-back. */
    fun isCallback(baseUrl: String, url: HttpUrl): Boolean {
        val base = baseUrl.toHttpUrl()
        return url.scheme == "https" && url.host == base.host && url.encodedPath == CALLBACK_PATH
    }
}
