package com.digitronics.chat.auth

import com.digitronics.chat.net.ApiClient
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.AuthorizationCodeTokenRequest
import com.digitronics.chat.net.DataEnvelope
import com.digitronics.chat.net.DeviceRegistrationDto
import com.digitronics.chat.net.RefreshTokenRequest
import com.digitronics.chat.net.TokenResponseDto
import java.io.IOException

/**
 * The public token endpoint. Called through a client WITHOUT the bearer
 * interceptor or authenticator: it must never recurse into a refresh.
 */
class AuthApi(
    private val api: ApiClient,
    private val clientId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun exchangeCode(code: String, verifier: String, redirectUri: String, device: DeviceRegistrationDto): TokenPair {
        val body = ApiJson.encodeToString(
            AuthorizationCodeTokenRequest.serializer(),
            AuthorizationCodeTokenRequest(
                clientId = clientId,
                code = code,
                codeVerifier = verifier,
                redirectUri = redirectUri,
                device = device,
            ),
        )
        return api.send("POST", TOKEN_PATH, body, DataEnvelope.serializer(TokenResponseDto.serializer())).data.toPair()
    }

    suspend fun refresh(refreshToken: String): RefreshOutcome = try {
        val body = ApiJson.encodeToString(
            RefreshTokenRequest.serializer(),
            RefreshTokenRequest(clientId = clientId, refreshToken = refreshToken),
        )
        RefreshOutcome.Success(
            api.send("POST", TOKEN_PATH, body, DataEnvelope.serializer(TokenResponseDto.serializer())).data.toPair(),
        )
    } catch (e: ApiException) {
        if (e.code == "invalid_grant" || (e.status in 400..499 && !e.isTransient)) {
            RefreshOutcome.InvalidGrant
        } else {
            RefreshOutcome.Transient(IOException("Token refresh failed (${e.status}).", e))
        }
    } catch (e: IOException) {
        RefreshOutcome.Transient(e)
    }

    private fun TokenResponseDto.toPair() = TokenPair(
        accessToken = accessToken,
        // Treat the token as expired a minute early so a request never races its expiry.
        accessExpiresAtMillis = now() + (expiresIn - 60).coerceAtLeast(30) * 1000,
        refreshToken = refreshToken,
        deviceId = deviceId,
        workspaceId = workspaceId,
    )

    companion object {
        const val TOKEN_PATH = "/api/v1/mobile/auth/token"
    }
}
