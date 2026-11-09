package com.digitronics.chat.update

import com.digitronics.chat.util.Base64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

class ManifestVerifierTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun manifest(edit: MutableMap<String, JsonElement>.() -> Unit = {}): JsonObject {
        val base = mutableMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(1),
            "releaseId" to JsonPrimitive("rel_1"),
            "packageId" to JsonPrimitive("com.digitronics.chat"),
            "channel" to JsonPrimitive("stable"),
            "versionCode" to JsonPrimitive(5),
            "versionName" to JsonPrimitive("1.1.0"),
            "minSdk" to JsonPrimitive(26),
            "abis" to kotlinx.serialization.json.JsonArray(emptyList()),
            "artifactBytes" to JsonPrimitive(1000),
            "sha256" to JsonPrimitive("a".repeat(64)),
            "apkSignerSha256" to JsonPrimitive("b".repeat(64)),
            "releasedAt" to JsonNull,
            "expiresAt" to JsonPrimitive("2026-11-10T12:00:00.000Z"),
            "minSupportedVersionCode" to JsonNull,
            "securityUpdate" to JsonPrimitive(false),
            "releaseNotes" to JsonPrimitive("Notes — é"),
            "canonicalDownloadUrl" to JsonPrimitive("https://messenger.digitronics.app/api/v1/mobile/releases/rel_1/download"),
            "deviceId" to JsonPrimitive("dev_1"),
            // A field this build does not know about: still covered by the signature.
            "futureField" to buildJsonObject { put("z", 1); put("a", "x") },
        )
        base.edit()
        return JsonObject(base)
    }

    private fun sign(manifest: JsonObject, keyPair: KeyPair = keys): String {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(CanonicalJson.encode(manifest).toByteArray(Charsets.UTF_8))
            sign()
        }
        return Base64Url.encode(der)
    }

    private fun offer(manifest: JsonObject, signature: String? = sign(manifest)) = JsonObject(
        mapOf("manifest" to manifest, "signature" to (signature?.let(::JsonPrimitive) ?: JsonNull), "keyId" to JsonPrimitive("k1")),
    )

    private fun verifier(installed: Int = 1, device: String? = "dev_1", withKey: Boolean = true) = ManifestVerifier(
        publicKey = if (withKey) keys.public else null,
        expectedPackageId = "com.digitronics.chat",
        installedVersionCode = installed,
        expectedDeviceId = device,
        trustedHost = "messenger.digitronics.app",
        expectedKeyId = "k1",
        now = { now },
    )

    private fun rejected(result: Verification): RejectReason = (result as Verification.Rejected).reason

    @Test
    fun `accepts a correctly signed manifest`() {
        val result = verifier().verify(offer(manifest()))
        assertTrue(result is Verification.Verified)
        assertEquals(5, (result as Verification.Verified).manifest.versionCode)
    }

    @Test
    fun `accepts a manifest signed by the Worker's own signManifest`() {
        val loader = requireNotNull(javaClass.classLoader)
        val response = Json.parseToJsonElement(loader.getResource("vectors/signed-update-check.json")!!.readText()).jsonObject
        val key = ManifestVerifier.parsePublicKey(loader.getResource("vectors/manifest-public-key.txt")!!.readText())
        val workerOffer = response["data"]!!.jsonObject["offer"]!!.jsonObject
        val v = ManifestVerifier(key, "com.digitronics.chat", 1, "dev_123456789", "messenger.digitronics.app") { now }
        assertTrue(v.verify(workerOffer) is Verification.Verified)
    }

    @Test
    fun `rejects when no key is pinned`() = assertEquals(RejectReason.NOT_CONFIGURED, rejected(verifier(withKey = false).verify(offer(manifest()))))

    @Test
    fun `rejects an unsigned manifest`() = assertEquals(RejectReason.UNSIGNED, rejected(verifier().verify(offer(manifest(), signature = null))))

    @Test
    fun `rejects a tampered manifest`() {
        val signed = manifest()
        val signature = sign(signed)
        val tampered = manifest { put("versionCode", JsonPrimitive(99)) }
        assertEquals(RejectReason.BAD_SIGNATURE, rejected(verifier().verify(offer(tampered, signature))))
    }

    @Test
    fun `rejects a change to an unknown field too`() {
        val signature = sign(manifest())
        val tampered = manifest { put("futureField", JsonPrimitive("changed")) }
        assertEquals(RejectReason.BAD_SIGNATURE, rejected(verifier().verify(offer(tampered, signature))))
    }

    @Test
    fun `rejects a signature from another key`() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val m = manifest()
        assertEquals(RejectReason.BAD_SIGNATURE, rejected(verifier().verify(offer(m, sign(m, other)))))
    }

    @Test
    fun `rejects garbage signature text`() =
        assertEquals(RejectReason.BAD_SIGNATURE, rejected(verifier().verify(offer(manifest(), "not*base64"))))

    @Test
    fun `rejects the wrong package`() {
        val m = manifest { put("packageId", JsonPrimitive("com.example.other")) }
        assertEquals(RejectReason.WRONG_PACKAGE, rejected(verifier().verify(offer(m))))
    }

    @Test
    fun `rejects an expired manifest`() {
        val m = manifest { put("expiresAt", JsonPrimitive("2026-11-09T11:59:59.000Z")) }
        assertEquals(RejectReason.EXPIRED, rejected(verifier().verify(offer(m))))
    }

    @Test
    fun `rejects a version that is not newer`() {
        assertEquals(RejectReason.NOT_NEWER, rejected(verifier(installed = 5).verify(offer(manifest()))))
        assertEquals(RejectReason.NOT_NEWER, rejected(verifier(installed = 6).verify(offer(manifest()))))
    }

    @Test
    fun `rejects a manifest computed for another device or for none`() {
        assertEquals(RejectReason.DEVICE_MISMATCH, rejected(verifier(device = "dev_2").verify(offer(manifest()))))
        val noDevice = manifest { remove("deviceId") }
        assertEquals(RejectReason.DEVICE_MISMATCH, rejected(verifier().verify(offer(noDevice))))
    }

    @Test
    fun `rejects a download URL off the pinned origin`() {
        val m = manifest { put("canonicalDownloadUrl", JsonPrimitive("https://evil.example/app.apk")) }
        assertEquals(RejectReason.UNTRUSTED_URL, rejected(verifier().verify(offer(m))))
        val http = manifest { put("canonicalDownloadUrl", JsonPrimitive("http://messenger.digitronics.app/x.apk")) }
        assertEquals(RejectReason.UNTRUSTED_URL, rejected(verifier().verify(offer(http))))
    }

    @Test
    fun `rejects an unknown schema version`() {
        val m = manifest { put("schemaVersion", JsonPrimitive(2)) }
        assertEquals(RejectReason.UNSUPPORTED_SCHEMA, rejected(verifier().verify(offer(m))))
    }

    @Test
    fun `rejects an offer signed under another key id`() {
        val m = manifest()
        val other = JsonObject(offer(m).toMutableMap().apply { put("keyId", JsonPrimitive("k2")) })
        assertEquals(RejectReason.UNKNOWN_KEY, rejected(verifier().verify(other)))
    }

    @Test
    fun `the pinned production key parses as EC P-256`() {
        val key = ManifestVerifier.parsePublicKey(com.digitronics.chat.BuildConfig.MANIFEST_PUBLIC_KEY_X509_B64)
        assertTrue(key is java.security.interfaces.ECPublicKey)
        assertEquals(256, (key as java.security.interfaces.ECPublicKey).params.order.bitLength())
        assertEquals("k1", com.digitronics.chat.BuildConfig.MANIFEST_KEY_ID)
    }

    @Test
    fun `public key parsing tolerates empty and junk input`() {
        assertEquals(null, ManifestVerifier.parsePublicKey(""))
        assertEquals(null, ManifestVerifier.parsePublicKey("not a key"))
    }
}
