package com.digitronics.chat.net

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Every route here is on the Worker's MOBILE_ROUTE_ALLOWLIST
 * (worker/src/lib/mobile-auth.ts). Anything else would be treated as
 * unauthenticated by the server, so the app never calls it.
 */
class DmApi(private val api: ApiClient) {
    suspend fun listConversations(cursor: String? = null, limit: Int = 50): ListConversationsResponse =
        api.get(
            "/api/v1/dm/conversations",
            mapOf("limit" to limit.toString(), "cursor" to cursor),
            ListConversationsResponse.serializer(),
        )

    /** `direction=before` walks history (newest first); `after` fetches the delta past [cursor]. */
    suspend fun listMessages(
        conversationId: String,
        direction: String,
        cursor: String?,
        limit: Int,
    ): ListMessagesResponse =
        api.get(
            "/api/v1/dm/conversations/${segment(conversationId)}/messages",
            mapOf("direction" to direction, "cursor" to cursor, "limit" to limit.toString()),
            ListMessagesResponse.serializer(),
        )

    suspend fun sendMessage(conversationId: String, request: SendMessageRequest): SendMessageResponse =
        api.send(
            "POST",
            "/api/v1/dm/conversations/${segment(conversationId)}/messages",
            ApiJson.encodeToString(SendMessageRequest.serializer(), request),
            SendMessageResponse.serializer(),
        )

    suspend fun markRead(conversationId: String, messageId: String) {
        api.send(
            "POST",
            "/api/v1/dm/conversations/${segment(conversationId)}/read",
            ApiJson.encodeToString(MarkReadRequest.serializer(), MarkReadRequest(messageId)),
            JsonObject.serializer(),
        )
    }

    companion object {
        const val REALTIME_PATH = "/api/v1/dm/realtime/connect"
    }
}

class MobileApi(private val api: ApiClient) {
    suspend fun session(): MobileSessionDto =
        api.get("/api/v1/mobile/session", emptyMap(), DataEnvelope.serializer(MobileSessionDto.serializer())).data

    suspend fun logout() {
        api.send("POST", "/api/v1/mobile/logout", "{}", JsonObject.serializer())
    }

    suspend fun registerPush(request: PushRegistrationRequest) {
        api.send(
            "PUT",
            "/api/v1/mobile/devices/current/push",
            ApiJson.encodeToString(PushRegistrationRequest.serializer(), request),
            JsonObject.serializer(),
        )
    }

    suspend fun testPush(): String =
        api.send(
            "POST",
            "/api/v1/mobile/devices/current/push/test",
            "{}",
            DataEnvelope.serializer(PushTestResultDto.serializer()),
        ).data.result

    /** Returns the raw `data` object; the update module verifies and maps it. */
    suspend fun checkForUpdate(): JsonObject =
        api.get("/api/v1/mobile/updates/check", emptyMap(), JsonObject.serializer())
}

/** One path segment, percent-encoded (ids are opaque strings from the server). */
internal fun segment(value: String): String =
    "https://x/".toHttpUrl().newBuilder().addPathSegment(value).build().encodedPathSegments.last()
