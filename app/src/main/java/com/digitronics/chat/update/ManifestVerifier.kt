package com.digitronics.chat.update

import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.ReleaseManifestDto
import com.digitronics.chat.util.Base64Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

enum class RejectReason {
    NOT_CONFIGURED,
    UNSIGNED,
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    WRONG_PACKAGE,
    EXPIRED,
    NOT_NEWER,
    DEVICE_MISMATCH,
    UNTRUSTED_URL,
}

sealed interface Verification {
    data class Verified(val manifest: ReleaseManifestDto) : Verification

    data class Rejected(val reason: RejectReason) : Verification
}

/**
 * Decides whether a signed update offer (`{ manifest, signature, keyId }`) may be
 * shown. The signature is DER ECDSA-P256 over SHA-256 of the canonical JSON,
 * base64url — what the Worker's `signManifest` produces — verified against the
 * public key pinned in this build. Everything after the signature check reads
 * only signed fields.
 */
class ManifestVerifier(
    private val publicKey: PublicKey?,
    private val expectedPackageId: String,
    private val installedVersionCode: Int,
    private val expectedDeviceId: String?,
    private val trustedHost: String,
    /** The key id pinned with [publicKey]; an offer naming another key is refused. */
    private val expectedKeyId: String? = null,
    private val now: () -> Instant = Instant::now,
) {
    fun verify(offer: JsonObject): Verification {
        val key = publicKey ?: return Verification.Rejected(RejectReason.NOT_CONFIGURED)
        val manifestJson = offer["manifest"] as? JsonObject ?: return Verification.Rejected(RejectReason.MALFORMED)
        val signatureText = (offer["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Verification.Rejected(RejectReason.UNSIGNED)

        val keyId = (offer["keyId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (expectedKeyId != null && keyId != null && keyId != expectedKeyId) {
            return Verification.Rejected(RejectReason.UNKNOWN_KEY)
        }

        val signatureBytes = runCatching { Base64Url.decode(signatureText) }.getOrNull()
            ?: return Verification.Rejected(RejectReason.BAD_SIGNATURE)
        val signedBytes = runCatching { CanonicalJson.encode(manifestJson).toByteArray(Charsets.UTF_8) }.getOrNull()
            ?: return Verification.Rejected(RejectReason.MALFORMED)
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(signedBytes)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        if (!valid) return Verification.Rejected(RejectReason.BAD_SIGNATURE)

        val schema = runCatching { manifestJson["schemaVersion"]?.jsonPrimitive?.content?.toInt() }.getOrNull()
        if (schema != 1) return Verification.Rejected(RejectReason.UNSUPPORTED_SCHEMA)
        val manifest = runCatching { ApiJson.decodeFromJsonElement(ReleaseManifestDto.serializer(), manifestJson) }.getOrNull()
            ?: return Verification.Rejected(RejectReason.MALFORMED)

        if (manifest.packageId != expectedPackageId) return Verification.Rejected(RejectReason.WRONG_PACKAGE)
        val expiresAt = runCatching { Instant.parse(manifest.expiresAt) }.getOrNull()
            ?: return Verification.Rejected(RejectReason.MALFORMED)
        if (!now().isBefore(expiresAt)) return Verification.Rejected(RejectReason.EXPIRED)
        if (manifest.versionCode <= installedVersionCode) return Verification.Rejected(RejectReason.NOT_NEWER)
        if (manifest.deviceId == null || manifest.deviceId != expectedDeviceId) {
            return Verification.Rejected(RejectReason.DEVICE_MISMATCH)
        }
        val url = manifest.canonicalDownloadUrl.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.host != trustedHost) {
            return Verification.Rejected(RejectReason.UNTRUSTED_URL)
        }
        return Verification.Verified(manifest)
    }

    companion object {
        /** X.509 SubjectPublicKeyInfo, standard or url-safe base64; null when empty or unusable. */
        fun parsePublicKey(x509Base64: String): PublicKey? {
            val text = x509Base64.trim()
            if (text.isEmpty()) return null
            val der = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
                ?: runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
                ?: return null
            return runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) }.getOrNull()
        }
    }
}
