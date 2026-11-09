package com.digitronics.chat.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.credentialStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

/** A sign-in started in the browser, waiting for its callback. */
data class PendingSignIn(val verifier: String, val state: String, val startedAtMillis: Long)

/**
 * Access token: memory only. Refresh token and the pending PKCE verifier:
 * sealed by [SecretCipher] (Android Keystore AES-GCM) in a DataStore file that
 * backup rules exclude.
 */
class SecureTokenStore(
    private val context: Context,
    private val cipher: SecretCipher,
    private val now: () -> Long = System::currentTimeMillis,
) : TokenStore {
    @Volatile private var access: Pair<String, Long>? = null

    private val store get() = context.credentialStore

    override fun currentAccessToken(): String? {
        val (token, expiresAt) = access ?: return null
        return if (now() < expiresAt) token else null
    }

    override suspend fun refreshToken(): String? =
        store.data.first()[REFRESH_SEALED]?.let { cipher.decrypt(it) }

    override suspend fun save(pair: TokenPair) {
        val sealed = cipher.encrypt(pair.refreshToken)
        store.edit {
            it[REFRESH_SEALED] = sealed
            it[DEVICE_ID] = pair.deviceId
            it[WORKSPACE_ID] = pair.workspaceId
        }
        access = pair.accessToken to pair.accessExpiresAtMillis
    }

    override suspend fun clear() {
        access = null
        store.edit {
            it.remove(REFRESH_SEALED)
            it.remove(DEVICE_ID)
            it.remove(WORKSPACE_ID)
            it.remove(PENDING_SEALED)
            it.remove(PENDING_STARTED)
        }
    }

    suspend fun hasRefreshToken(): Boolean = store.data.first()[REFRESH_SEALED] != null

    suspend fun deviceId(): String? = store.data.first()[DEVICE_ID]

    suspend fun savePending(pending: PendingSignIn) {
        val sealed = cipher.encrypt(pending.verifier + "\n" + pending.state)
        store.edit {
            it[PENDING_SEALED] = sealed
            it[PENDING_STARTED] = pending.startedAtMillis
        }
    }

    suspend fun takePending(): PendingSignIn? {
        val prefs = store.data.first()
        val sealed = prefs[PENDING_SEALED] ?: return null
        val started = prefs[PENDING_STARTED] ?: return null
        store.edit {
            it.remove(PENDING_SEALED)
            it.remove(PENDING_STARTED)
        }
        val opened = cipher.decrypt(sealed) ?: return null
        val parts = opened.split("\n")
        if (parts.size != 2) return null
        return PendingSignIn(parts[0], parts[1], started)
    }

    private companion object {
        val REFRESH_SEALED = stringPreferencesKey("refresh_sealed")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val WORKSPACE_ID = stringPreferencesKey("workspace_id")
        val PENDING_SEALED = stringPreferencesKey("pending_signin_sealed")
        val PENDING_STARTED = longPreferencesKey("pending_signin_started")
    }
}
