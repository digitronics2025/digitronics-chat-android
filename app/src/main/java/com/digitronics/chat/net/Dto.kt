package com.digitronics.chat.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire contracts mirrored from whatsapp-inbox-saas:
 *   packages/shared/src/mobile.ts  (mobile auth, devices, push, updates)
 *   packages/shared/src/schemas.ts (DM conversations and messages)
 * The server only ever ADDS fields, so every decode ignores unknown keys
 * (see ApiJson) and every optional field has a default.
 */

@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class ApiErrorBody(val error: String? = null, val message: String? = null)

// ── Mobile auth ──────────────────────────────────────────────────────────────

@Serializable
data class DeviceRegistrationDto(
    val installId: String,
    val label: String?,
    val appVersionCode: Int,
    val appVersionName: String,
    val sdkInt: Int,
    val channel: String,
)

@Serializable
data class AuthorizationCodeTokenRequest(
    val grantType: String = "authorization_code",
    val clientId: String,
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val device: DeviceRegistrationDto,
)

@Serializable
data class RefreshTokenRequest(
    val grantType: String = "refresh_token",
    val clientId: String,
    val refreshToken: String,
)

@Serializable
data class TokenResponseDto(
    val tokenType: String,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val refreshExpiresIn: Long,
    val deviceId: String,
    val workspaceId: String,
)

@Serializable
data class SessionUserDto(val id: String, val email: String, val fullName: String)

@Serializable
data class SessionWorkspaceDto(val id: String, val name: String, val role: String)

@Serializable
data class MobileDeviceDto(
    val id: String,
    val label: String? = null,
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val notificationsEnabled: Boolean = false,
    val pushRegistered: Boolean = false,
    val current: Boolean = false,
)

@Serializable
data class MobileSessionDto(
    val user: SessionUserDto,
    val workspace: SessionWorkspaceDto,
    val canSendDirectMessages: Boolean,
    val device: MobileDeviceDto,
    val accessExpiresAt: String? = null,
)

// ── Push ─────────────────────────────────────────────────────────────────────

@Serializable
data class PushRegistrationRequest(val token: String?, val notificationsEnabled: Boolean)

@Serializable
data class PushTestResultDto(val result: String)

// ── Updates ──────────────────────────────────────────────────────────────────

/** `offer` stays raw JSON: the signature covers its exact canonical form, unknown fields included. */
@Serializable
data class UpdateCheckDto(
    val status: String,
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int? = null,
    val offer: JsonObject? = null,
    val checkedAt: String? = null,
)

@Serializable
data class ReleaseManifestDto(
    val schemaVersion: Int,
    val releaseId: String,
    val packageId: String,
    val channel: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val abis: List<String> = emptyList(),
    val artifactBytes: Long,
    val sha256: String,
    val apkSignerSha256: String,
    val releasedAt: String? = null,
    val expiresAt: String,
    val minSupportedVersionCode: Int? = null,
    val securityUpdate: Boolean = false,
    val releaseNotes: String = "",
    val canonicalDownloadUrl: String,
    val deviceId: String? = null,
)

// ── Direct messages ──────────────────────────────────────────────────────────

@Serializable
data class DmPeerDto(
    val userId: String,
    val handle: String? = null,
    val displayName: String,
    val isBot: Boolean = false,
    val lastSeenAt: String? = null,
)

@Serializable
data class DmGroupDto(
    val id: String,
    val title: String,
    val memberCount: Int = 0,
    val role: String? = null,
    val lastMessageSenderName: String? = null,
)

@Serializable
data class DmConversationDto(
    val id: String,
    val peer: DmPeerDto? = null,
    val group: DmGroupDto? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderId: String? = null,
    val unreadCount: Int = 0,
    val lastReadMessageId: String? = null,
    val peerLastReadMessageId: String? = null,
    val archivedAt: String? = null,
    val mutedUntil: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ListConversationsResponse(
    val conversations: List<DmConversationDto>,
    val nextCursor: String? = null,
)

@Serializable
data class DmSenderDto(val userId: String, val displayName: String)

@Serializable
data class DmMediaDto(
    val assetId: String,
    val mimeType: String,
    val fileName: String? = null,
)

@Serializable
data class DmMessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String? = null,
    val sender: DmSenderDto? = null,
    val body: String,
    val messageType: String,
    val systemTitle: String? = null,
    val systemSeverity: String? = null,
    val systemDeepLink: String? = null,
    val systemBlocks: List<JsonElement>? = null,
    val media: DmMediaDto? = null,
    val clientMessageId: String? = null,
    val deliveredAt: String? = null,
    val readAt: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
)

@Serializable
data class ListMessagesResponse(
    val messages: List<DmMessageDto>,
    val nextCursor: String? = null,
    val headCursor: String? = null,
)

@Serializable
data class SendMessageRequest(val body: String, val clientMessageId: String)

@Serializable
data class SendMessageResponse(val message: DmMessageDto)

@Serializable
data class MarkReadRequest(val messageId: String)

/** Realtime frames: only the fields the app reacts to. */
@Serializable
data class RealtimeFrame(
    val type: String,
    val conversationId: String? = null,
    val messageId: String? = null,
    @SerialName("lastReadMessageId") val lastReadMessageId: String? = null,
)
