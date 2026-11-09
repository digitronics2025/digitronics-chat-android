package com.digitronics.chat.update

import com.digitronics.chat.net.ReleaseManifestDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateMapperTest {
    private fun data(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun manifest(minSupported: Int? = null) = ReleaseManifestDto(
        schemaVersion = 1, releaseId = "r", packageId = "com.digitronics.chat", channel = "stable",
        versionCode = 9, versionName = "2.0", minSdk = 26, artifactBytes = 1, sha256 = "a".repeat(64),
        apkSignerSha256 = "b".repeat(64), expiresAt = "2026-11-10T00:00:00Z", minSupportedVersionCode = minSupported,
        canonicalDownloadUrl = "https://messenger.digitronics.app/x",
    )

    private val accept: (JsonObject) -> Verification = { Verification.Verified(manifest()) }
    private val offer = """{"manifest":{},"signature":"x","keyId":"k1"}"""

    @Test
    fun `no release`() = assertEquals(
        UpdateState.NoRelease,
        UpdateStateMapper.map(data("""{"status":"no_release","mandatory":false,"offer":null,"checkedAt":"t"}"""), 1, accept),
    )

    @Test
    fun `up to date`() = assertEquals(
        UpdateState.UpToDate,
        UpdateStateMapper.map(data("""{"status":"up_to_date","mandatory":false,"offer":null,"checkedAt":"t"}"""), 1, accept),
    )

    @Test
    fun `below the minimum with no offer for this phone`() = assertEquals(
        UpdateState.UnsupportedNoOffer,
        UpdateStateMapper.map(data("""{"status":"up_to_date","mandatory":true,"offer":null,"checkedAt":"t"}"""), 1, accept),
    )

    @Test
    fun `verified offer becomes Available`() {
        val state = UpdateStateMapper.map(data("""{"status":"update_available","mandatory":false,"offer":$offer}"""), 1, accept)
        assertTrue(state is UpdateState.Available)
        assertFalse((state as UpdateState.Available).mandatory)
    }

    @Test
    fun `mandatory comes from the server flag or the signed minimum`() {
        val byFlag = UpdateStateMapper.map(data("""{"status":"update_available","mandatory":true,"offer":$offer}"""), 1, accept)
        assertTrue((byFlag as UpdateState.Available).mandatory)
        val bySignedMinimum = UpdateStateMapper.map(
            data("""{"status":"update_available","mandatory":false,"offer":$offer}"""),
            3,
        ) { Verification.Verified(manifest(minSupported = 4)) }
        assertTrue((bySignedMinimum as UpdateState.Available).mandatory)
    }

    @Test
    fun `a rejected offer is never Available`() {
        val state = UpdateStateMapper.map(
            data("""{"status":"update_available","mandatory":true,"offer":$offer}"""),
            1,
        ) { Verification.Rejected(RejectReason.BAD_SIGNATURE) }
        assertEquals(UpdateState.Rejected(RejectReason.BAD_SIGNATURE), state)
    }

    @Test
    fun `update_available without an offer is treated as unsigned`() = assertEquals(
        UpdateState.Rejected(RejectReason.UNSIGNED),
        UpdateStateMapper.map(data("""{"status":"update_available","mandatory":false,"offer":null}"""), 1, accept),
    )

    @Test
    fun `unknown status and malformed data fail closed`() {
        assertEquals(UpdateState.Failed(null), UpdateStateMapper.map(data("""{"status":"surprise"}"""), 1, accept))
        assertEquals(UpdateState.Failed(null), UpdateStateMapper.map(data("""{"nothing":true}"""), 1, accept))
    }

    @Test
    fun `newer server fields are ignored`() = assertEquals(
        UpdateState.UpToDate,
        UpdateStateMapper.map(data("""{"status":"up_to_date","mandatory":false,"offer":null,"newField":{"a":1}}"""), 1, accept),
    )
}
