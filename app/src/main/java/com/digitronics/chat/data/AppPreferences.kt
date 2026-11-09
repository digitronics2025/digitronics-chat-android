package com.digitronics.chat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.digitronics.chat.net.ApiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The signed-in person as the app shows them. `key` namespaces every cached row. */
@Serializable
data class Account(
    val userId: String,
    val email: String,
    val fullName: String,
    val workspaceId: String,
    val workspaceName: String,
    val role: String,
    val deviceId: String,
    val canSend: Boolean,
) {
    val key: String get() = "$userId@$workspaceId"
}

/** Non-secret preferences. Secrets live in [com.digitronics.chat.auth.SecureTokenStore]. */
class AppPreferences(private val context: Context) {
    private val store get() = context.settingsStore

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    /** A random per-install id: not a secret, not an authority (mobile-auth.md). */
    suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        store.edit { prefs -> if (prefs[INSTALL_ID] == null) prefs[INSTALL_ID] = fresh }
        return store.data.first()[INSTALL_ID] ?: fresh
    }

    suspend fun account(): Account? = store.data.first()[ACCOUNT]?.let {
        runCatching { ApiJson.decodeFromString(Account.serializer(), it) }.getOrNull()
    }

    suspend fun setAccount(account: Account?) {
        store.edit { prefs ->
            if (account == null) prefs.remove(ACCOUNT) else prefs[ACCOUNT] = ApiJson.encodeToString(Account.serializer(), account)
        }
    }

    val notificationPromptDismissed: Flow<Boolean> = store.data.map { it[PROMPT_DISMISSED] ?: false }

    suspend fun setNotificationPromptDismissed(value: Boolean) {
        store.edit { it[PROMPT_DISMISSED] = value }
    }

    suspend fun pushRegistrationFingerprint(): String? = store.data.first()[PUSH_FINGERPRINT]

    suspend fun setPushRegistrationFingerprint(value: String?) {
        store.edit { if (value == null) it.remove(PUSH_FINGERPRINT) else it[PUSH_FINGERPRINT] = value }
    }

    val pushRegistered: Flow<Boolean> = store.data.map { it[PUSH_FINGERPRINT] != null }

    suspend fun lastUpdateCheck(): Pair<Long, String>? {
        val prefs = store.data.first()
        val at = prefs[UPDATE_CHECKED_AT] ?: return null
        val body = prefs[UPDATE_RESPONSE] ?: return null
        return at to body
    }

    suspend fun setLastUpdateCheck(atMillis: Long, responseJson: String) {
        store.edit {
            it[UPDATE_CHECKED_AT] = atMillis
            it[UPDATE_RESPONSE] = responseJson
        }
    }

    suspend fun lastNotifiedUpdateVersion(): Int = store.data.first()[UPDATE_NOTIFIED_VERSION] ?: 0

    suspend fun setLastNotifiedUpdateVersion(versionCode: Int) {
        store.edit { it[UPDATE_NOTIFIED_VERSION] = versionCode }
    }

    /** Sign-out: forget everything about the person; keep the install id and the theme. */
    suspend fun clearAccountData() {
        store.edit { prefs ->
            prefs.remove(ACCOUNT)
            prefs.remove(PUSH_FINGERPRINT)
            prefs.remove(UPDATE_CHECKED_AT)
            prefs.remove(UPDATE_RESPONSE)
            prefs.remove(PROMPT_DISMISSED)
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val INSTALL_ID = stringPreferencesKey("install_id")
        val ACCOUNT = stringPreferencesKey("account")
        val PROMPT_DISMISSED = booleanPreferencesKey("notification_prompt_dismissed")
        val PUSH_FINGERPRINT = stringPreferencesKey("push_registration_fingerprint")
        val UPDATE_CHECKED_AT = longPreferencesKey("update_checked_at")
        val UPDATE_RESPONSE = stringPreferencesKey("update_response")
        val UPDATE_NOTIFIED_VERSION = intPreferencesKey("update_notified_version")
    }
}
