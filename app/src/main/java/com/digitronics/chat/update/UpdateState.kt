package com.digitronics.chat.update

import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.ReleaseManifestDto
import com.digitronics.chat.net.UpdateCheckDto
import kotlinx.serialization.json.JsonObject

/** Everything the App Updates screen can show. */
sealed interface UpdateState {
    /** No manifest public key in this build: never offer anything. */
    data object NotConfigured : UpdateState

    data object NeverChecked : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data object NoRelease : UpdateState

    /** The installed build is below the published minimum but no offer reached this phone. */
    data object UnsupportedNoOffer : UpdateState

    data class Available(
        val manifest: ReleaseManifestDto,
        val mandatory: Boolean,
    ) : UpdateState

    /** The server offered something this build refuses to show. */
    data class Rejected(val reason: RejectReason) : UpdateState

    data object Offline : UpdateState

    data class Failed(val status: Int?) : UpdateState
}

object UpdateStateMapper {
    /**
     * Maps the `data` object of `GET /api/v1/mobile/updates/check`. Only a
     * verified manifest becomes [UpdateState.Available]; `mandatory` is true when
     * the server says so OR the signed manifest's minimum is above us.
     */
    fun map(data: JsonObject, installedVersionCode: Int, verify: (JsonObject) -> Verification): UpdateState {
        val dto = runCatching { ApiJson.decodeFromJsonElement(UpdateCheckDto.serializer(), data) }.getOrNull()
            ?: return UpdateState.Failed(null)
        return when (dto.status) {
            "no_release" -> UpdateState.NoRelease
            "up_to_date" -> if (dto.mandatory) UpdateState.UnsupportedNoOffer else UpdateState.UpToDate
            "update_available" -> {
                val offer = dto.offer ?: return UpdateState.Rejected(RejectReason.UNSIGNED)
                when (val result = verify(offer)) {
                    is Verification.Verified -> {
                        val signedMinimum = result.manifest.minSupportedVersionCode
                        val mandatory = dto.mandatory || (signedMinimum != null && installedVersionCode < signedMinimum)
                        UpdateState.Available(result.manifest, mandatory)
                    }
                    is Verification.Rejected -> UpdateState.Rejected(result.reason)
                }
            }
            else -> UpdateState.Failed(null)
        }
    }
}
