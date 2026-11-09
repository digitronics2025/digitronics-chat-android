package com.digitronics.chat.auth

import com.digitronics.chat.net.ApiClient
import com.digitronics.chat.net.DmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real OkHttp stack (bearer interceptor + authenticator + refresher)
 * against a fake server: parallel requests that all hit 401 must cause ONE
 * refresh, because a second use of the rotated refresh token would make the
 * server revoke the device.
 */
class AuthenticatorIntegrationTest {
    private val server = MockWebServer()
    private val tokenCalls = AtomicInteger()
    private val presentedRefreshTokens = Collections.synchronizedList(mutableListOf<String>())
    private val versionHeaders = Collections.synchronizedList(mutableListOf<String?>())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                versionHeaders += request.getHeader("X-App-Version-Code")
                return when (request.path?.substringBefore('?')) {
                    "/api/v1/mobile/auth/token" -> {
                        tokenCalls.incrementAndGet()
                        val body = request.body.readUtf8()
                        presentedRefreshTokens += Regex("\"refreshToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                        if (!body.contains("\"grantType\":\"refresh_token\"") || !body.contains("\"refreshToken\":\"dcm_rt_1\"")) {
                            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","message":"no"}""")
                        } else {
                            Thread.sleep(100)
                            MockResponse().setBody(
                                """{"data":{"tokenType":"Bearer","accessToken":"dcm_at_new","expiresIn":900,"refreshToken":"dcm_rt_2","refreshExpiresIn":2592000,"deviceId":"dev_1","workspaceId":"ws_1"}}""",
                            )
                        }
                    }
                    "/api/v1/dm/conversations" ->
                        if (request.getHeader("Authorization") == "Bearer dcm_at_new") {
                            MockResponse().setBody("""{"conversations":[],"nextCursor":null}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","message":"Sign in"}""")
                        }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    // Shares one OkHttp client (and dispatcher) between the token call and the
    // authed calls on purpose, with more than OkHttp's 5-per-host async limit:
    // this deadlocked before ApiClient switched to synchronous execution.
    @Test(timeout = 30_000)
    fun `parallel 401s refresh once and every request succeeds`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val host = server.hostName
        val store = FakeTokenStore(access = "dcm_at_expired", refresh = "dcm_rt_1")
        val ended = AtomicInteger()
        val plain = OkHttpClient.Builder().addInterceptor(AppVersionInterceptor(42)).build()
        val authApi = AuthApi(ApiClient(plain, base), "digitronics-chat-android")
        val refresher = TokenRefresher(store, authApi::refresh) { ended.incrementAndGet() }
        val authed = plain.newBuilder()
            .addInterceptor(BearerInterceptor(store, refresher, host))
            .authenticator(RefreshAuthenticator(refresher, host))
            .build()
        val dm = DmApi(ApiClient(authed, base))

        val results = (1..12).map { async(Dispatchers.IO) { dm.listConversations() } }.awaitAll()

        assertEquals(12, results.size)
        assertEquals(1, tokenCalls.get())
        assertEquals(listOf("dcm_rt_1"), presentedRefreshTokens.toList())
        assertEquals("dcm_rt_2", store.refresh)
        assertEquals(0, ended.get())
        assertTrue("every request carries the app version", versionHeaders.all { it == "42" })
    }

    @Test(timeout = 30_000)
    fun `a refused refresh ends the session instead of looping`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val host = server.hostName
        val store = FakeTokenStore(access = "dcm_at_expired", refresh = "dcm_rt_revoked")
        val ended = AtomicInteger()
        val plain = OkHttpClient()
        val refresher = TokenRefresher(store, AuthApi(ApiClient(plain, base), "digitronics-chat-android")::refresh) { ended.incrementAndGet() }
        val authed = plain.newBuilder()
            .addInterceptor(BearerInterceptor(store, refresher, host))
            .authenticator(RefreshAuthenticator(refresher, host))
            .build()

        val failure = runCatching { DmApi(ApiClient(authed, base)).listConversations() }.exceptionOrNull()

        assertTrue(failure is com.digitronics.chat.net.ApiException && failure.status == 401)
        assertEquals(1, tokenCalls.get())
        assertEquals(1, ended.get())
        assertEquals(null, store.refresh)
    }
}
