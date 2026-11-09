package com.digitronics.chat.auth

/** One device-bound pair from `POST /api/v1/mobile/auth/token`. */
data class TokenPair(
    val accessToken: String,
    val accessExpiresAtMillis: Long,
    val refreshToken: String,
    val deviceId: String,
    val workspaceId: String,
)

/**
 * Where the pair lives. The access token is memory-only (a process restart
 * simply refreshes); the refresh token is sealed with [SecretCipher].
 */
interface TokenStore {
    /** The access token if it is still comfortably valid, else null. */
    fun currentAccessToken(): String?

    suspend fun refreshToken(): String?

    suspend fun save(pair: TokenPair)

    suspend fun clear()
}

sealed interface RefreshOutcome {
    data class Success(val pair: TokenPair) : RefreshOutcome

    /** The server refused the grant: the session is over (revoked, expired or replayed). */
    data object InvalidGrant : RefreshOutcome

    /** Network or server trouble; the session is still believed valid. */
    data class Transient(val cause: java.io.IOException) : RefreshOutcome
}
