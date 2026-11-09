package com.digitronics.chat.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** `X-App-Version-Code` on every request, so the server can see which build is asking. */
class AppVersionInterceptor(private val versionCode: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(HEADER_VERSION_CODE, versionCode.toString()).build())

    companion object {
        const val HEADER_VERSION_CODE = "X-App-Version-Code"
    }
}

/**
 * Attaches `Authorization: Bearer <access>` to requests for our own host only.
 * With no live access token but a stored refresh token (cold start, or past the
 * 15 minutes) it refreshes first, through the shared [TokenRefresher].
 */
class BearerInterceptor(
    private val store: TokenStore,
    private val refresher: TokenRefresher,
    private val apiHost: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != apiHost) return chain.proceed(request)
        val token = store.currentAccessToken() ?: runBlocking { refresher.refresh(null) }
        return chain.proceed(withBearer(request, token))
    }
}

/**
 * OkHttp calls this on a 401. It refreshes once (serialised by the refresher)
 * and retries the request with the new token; a second 401 gives up so a
 * revoked or off-allowlist call can never loop.
 */
class RefreshAuthenticator(
    private val refresher: TokenRefresher,
    private val apiHost: String,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.host != apiHost) return null
        if (priorResponses(response) >= 1) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val fresh = runBlocking { refresher.refresh(failed) } ?: return null
        if (fresh == failed) return null
        return withBearer(response.request, fresh)
    }

    private fun priorResponses(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

internal fun withBearer(request: Request, token: String?): Request =
    if (token == null) request else request.newBuilder().header("Authorization", "Bearer $token").build()
