package com.digitronics.chat.data.db

import androidx.room.Entity
import androidx.room.Index

/*
 * Room is a bounded CACHE, never the source of truth: every row is keyed by
 * the account (`userId@workspaceId`) so one person can never see another's
 * thread, and sign-out clears every table.
 */

@Entity(tableName = "conversations", primaryKeys = ["accountKey", "id"])
data class ConversationEntity(
    val accountKey: String,
    val id: String,
    val isGroup: Boolean,
    val isBot: Boolean,
    val title: String,
    val peerUserId: String?,
    val memberCount: Int,
    val lastMessageAt: String?,
    val lastMessagePreview: String?,
    val lastMessageSenderId: String?,
    val lastMessageSenderName: String?,
    val unreadCount: Int,
    val lastReadMessageId: String?,
    val peerLastReadMessageId: String?,
    val mutedUntil: String?,
    val updatedAt: String,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["accountKey", "id"],
    indices = [Index(value = ["accountKey", "conversationId", "createdAt"])],
)
data class MessageEntity(
    val accountKey: String,
    val id: String,
    val conversationId: String,
    val senderId: String?,
    val senderName: String?,
    val body: String,
    val messageType: String,
    val systemTitle: String?,
    val systemSeverity: String?,
    /** Raw JSON array of report-card blocks, or null. */
    val systemBlocksJson: String?,
    val mediaFileName: String?,
    val clientMessageId: String?,
    val readAt: String?,
    val deletedAt: String?,
    val createdAt: String,
)

/** Per-thread sync position: the server-owned head cursor and the paging-back cursor. */
@Entity(tableName = "thread_cursors", primaryKeys = ["accountKey", "conversationId"])
data class ThreadCursorEntity(
    val accountKey: String,
    val conversationId: String,
    val headCursor: String?,
    val olderCursor: String?,
    val reachedStart: Boolean,
)

/** A message typed on this phone that the server has not confirmed yet. */
@Entity(tableName = "outgoing", primaryKeys = ["accountKey", "clientMessageId"])
data class OutgoingMessageEntity(
    val accountKey: String,
    val clientMessageId: String,
    val conversationId: String,
    val body: String,
    val state: String,
    val error: String?,
    val createdAtMillis: Long,
) {
    companion object {
        const val STATE_SENDING = "sending"
        const val STATE_FAILED = "failed"
    }
}
