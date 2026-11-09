package com.digitronics.chat.update

import com.digitronics.chat.BuildConfig
import com.digitronics.chat.data.AppPreferences
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.MobileApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/**
 * Asks the server whether a newer build exists and turns the answer into an
 * [UpdateState]. Nothing is shown unless the manifest verifies against the key
 * pinned in BuildConfig; without that key, checking is off entirely.
 */
class UpdateRepository(
    private val prefs: AppPreferences,
    private val mobileApi: MobileApi,
    private val deviceId: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val publicKey = ManifestVerifier.parsePublicKey(BuildConfig.MANIFEST_PUBLIC_KEY_X509_B64)
    val isConfigured: Boolean get() = publicKey != null

    private val _state = MutableStateFlow<UpdateState>(if (publicKey == null) UpdateState.NotConfigured else UpdateState.NeverChecked)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _lastCheckedAt = MutableStateFlow<Long?>(null)
    val lastCheckedAt: StateFlow<Long?> = _lastCheckedAt.asStateFlow()

    /** Shows the last stored answer again, re-verified (an expired manifest drops out). */
    suspend fun restore() {
        if (publicKey == null) return
        val (at, body) = prefs.lastUpdateCheck() ?: return
        val data = runCatching { ApiJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return
        _lastCheckedAt.value = at
        _state.value = map(data)
    }

    suspend fun check(): UpdateState {
        if (publicKey == null) return UpdateState.NotConfigured.also { _state.value = it }
        _state.value = UpdateState.Checking
        val result = try {
            val root = mobileApi.checkForUpdate()
            val data = root["data"] as? JsonObject ?: throw ApiException(200, "bad_response", "No data.")
            val at = now()
            prefs.setLastUpdateCheck(at, data.toString())
            _lastCheckedAt.value = at
            map(data)
        } catch (e: ApiException) {
            UpdateState.Failed(e.status)
        } catch (e: IOException) {
            UpdateState.Offline
        }
        _state.value = result
        return result
    }

    private suspend fun map(data: JsonObject): UpdateState {
        val verifier = ManifestVerifier(
            publicKey = publicKey,
            expectedPackageId = BuildConfig.RELEASE_PACKAGE_ID,
            installedVersionCode = BuildConfig.VERSION_CODE,
            expectedDeviceId = deviceId(),
            trustedHost = BuildConfig.BASE_URL.toHttpUrl().host,
            expectedKeyId = BuildConfig.MANIFEST_KEY_ID.ifBlank { null },
        )
        return UpdateStateMapper.map(data, BuildConfig.VERSION_CODE, verifier::verify)
    }

    fun reset() {
        _state.value = if (publicKey == null) UpdateState.NotConfigured else UpdateState.NeverChecked
        _lastCheckedAt.value = null
    }
}
