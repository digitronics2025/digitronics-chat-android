package com.digitronics.chat.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [TokenStore] for tests. */
class FakeTokenStore(access: String?, refresh: String?) : TokenStore {
    @Volatile var access: String? = access
    @Volatile var refresh: String? = refresh
    val cleared = AtomicInteger()

    override fun currentAccessToken(): String? = access

    override suspend fun refreshToken(): String? = refresh

    override suspend fun save(pair: TokenPair) {
        refresh = pair.refreshToken
        access = pair.accessToken
    }

    override suspend fun clear() {
        cleared.incrementAndGet()
        access = null
        refresh = null
    }
}

fun pair(access: String, refresh: String) = TokenPair(access, Long.MAX_VALUE, refresh, "dev_1", "ws_1")

class TokenRefresherTest {
    @Test
    fun `concurrent 401s trigger exactly one refresh and share its result`() = runBlocking {
        val store = FakeTokenStore(access = "dcm_at_old", refresh = "dcm_rt_1")
        val exchanges = AtomicInteger()
        val presented = mutableListOf<String>()
        val refresher = TokenRefresher(
            store = store,
            exchange = { token ->
                synchronized(presented) { presented += token }
                exchanges.incrementAndGet()
                delay(50) // a slow network makes the race real
                RefreshOutcome.Success(pair("dcm_at_new", "dcm_rt_2"))
            },
            onSessionEnded = { fail("session must not end") },
        )

        val results = (1..20).map { async(Dispatchers.Default) { refresher.refresh("dcm_at_old") } }.awaitAll()

        assertEquals(1, exchanges.get())
        assertEquals(listOf("dcm_rt_1"), presented) // the rotated refresh token is never presented twice
        assertTrue(results.all { it == "dcm_at_new" })
        assertEquals("dcm_rt_2", store.refresh)
    }

    @Test
    fun `a token already replaced by another caller is returned without a network call`() = runBlocking {
        val store = FakeTokenStore(access = "dcm_at_fresh", refresh = "dcm_rt_1")
        val refresher = TokenRefresher(store, { fail("no exchange expected"); RefreshOutcome.InvalidGrant }, {})
        assertEquals("dcm_at_fresh", refresher.refresh("dcm_at_stale"))
    }

    @Test
    fun `invalid_grant clears tokens and ends the session once`() = runBlocking {
        val store = FakeTokenStore(access = null, refresh = "dcm_rt_reused")
        val ended = AtomicInteger()
        val exchanges = AtomicInteger()
        val refresher = TokenRefresher(store, { exchanges.incrementAndGet(); RefreshOutcome.InvalidGrant }, { ended.incrementAndGet() })

        val results = (1..5).map { async(Dispatchers.Default) { refresher.refresh(null) } }.awaitAll()

        assertTrue(results.all { it == null })
        assertEquals(1, exchanges.get())
        assertEquals(1, ended.get())
        assertNull(store.refresh)
        assertEquals(1, store.cleared.get())
    }

    @Test
    fun `transient failure surfaces as IOException and keeps the session`() = runBlocking {
        val store = FakeTokenStore(access = null, refresh = "dcm_rt_1")
        val refresher = TokenRefresher(store, { RefreshOutcome.Transient(IOException("offline")) }, { fail("must not end") })
        try {
            refresher.refresh(null)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("dcm_rt_1", store.refresh)
        }
    }

    @Test
    fun `no refresh token means no session and no network`() = runBlocking {
        val store = FakeTokenStore(access = null, refresh = null)
        val refresher = TokenRefresher(store, { fail("no exchange expected"); RefreshOutcome.InvalidGrant }, {})
        assertNull(refresher.refresh(null))
    }
}
