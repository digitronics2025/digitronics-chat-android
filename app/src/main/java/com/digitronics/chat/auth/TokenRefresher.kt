package com.digitronics.chat.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Serialises every refresh behind one [Mutex]. This is a correctness rule, not
 * an optimisation: refresh tokens rotate on every use and the server treats a
 * reused one as theft and revokes the whole device. Two refreshes in flight
 * would present the same token twice.
 *
 * Callers pass the access token that just failed (or null when they had none).
 * If another caller already replaced it while we waited for the lock, the new
 * token is returned without touching the network.
 */
class TokenRefresher(
    private val store: TokenStore,
    private val exchange: suspend (refreshToken: String) -> RefreshOutcome,
    private val onSessionEnded: suspend () -> Unit,
) {
    private val mutex = Mutex()

    /**
     * @return a usable access token, or null when there is no session (signed
     *   out, or the server refused the refresh — [onSessionEnded] has then run).
     * @throws IOException on a transient failure, so the caller fails like any
     *   network error instead of looking signed out.
     */
    suspend fun refresh(failedAccessToken: String?): String? = mutex.withLock {
        val current = store.currentAccessToken()
        if (current != null && current != failedAccessToken) return@withLock current

        val refreshToken = store.refreshToken() ?: return@withLock null
        when (val outcome = exchange(refreshToken)) {
            is RefreshOutcome.Success -> {
                store.save(outcome.pair)
                outcome.pair.accessToken
            }
            RefreshOutcome.InvalidGrant -> {
                store.clear()
                onSessionEnded()
                null
            }
            is RefreshOutcome.Transient -> throw outcome.cause
        }
    }
}
