package com.digitronics.chat.data

import androidx.room.withTransaction
import com.digitronics.chat.data.db.ChatDatabase
import com.digitronics.chat.data.db.ConversationEntity
import com.digitronics.chat.data.db.MessageEntity
import com.digitronics.chat.data.db.OutgoingMessageEntity
import com.digitronics.chat.data.db.ThreadCursorEntity
import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.DmApi
import com.digitronics.chat.net.DmConversationDto
import com.digitronics.chat.net.DmMessageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat data: the server is the truth, Room a bounded per-account cache.
 *
 * Sync rules (docs/systems/sync.md):
 *  - first open of a thread loads the newest page (`direction=before`, no cursor)
 *    and stores the server's `headCursor` and the paging-back `nextCursor`;
 *  - every later sync drains `direction=after&cursor=<headCursor>` pages;
 *  - messages merge by immutable server id; outgoing rows merge by `clientMessageId`.
 */
class ChatRepository(
    private val db: ChatDatabase,
    private val dmApi: DmApi,
    private val enqueueSend: (accountKey: String, clientMessageId: String) -> Unit,
) {
    private val dao = db.chatDao()
    private val threadLocks = ConcurrentHashMap<String, Mutex>()

    fun conversations(account: Account): Flow<List<ConversationEntity>> = dao.observeConversations(account.key)

    fun conversation(account: Account, id: String): Flow<ConversationEntity?> = dao.observeConversation(account.key, id)

    fun messages(account: Account, conversationId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(account.key, conversationId)

    fun outgoing(account: Account, conversationId: String): Flow<List<OutgoingMessageEntity>> =
        dao.observeOutgoing(account.key, conversationId)

    fun cursor(account: Account, conversationId: String): Flow<ThreadCursorEntity?> =
        dao.observeCursor(account.key, conversationId)

    suspend fun message(account: Account, id: String): MessageEntity? = dao.message(account.key, id)

    suspend fun conversationOnce(account: Account, id: String): ConversationEntity? = dao.conversation(account.key, id)

    /** Fetch the conversation list (up to [MAX_CONVERSATIONS]) and drop rows the server no longer lists. */
    suspend fun refreshConversations(account: Account, openConversationId: String? = null) {
        val all = mutableListOf<DmConversationDto>()
        var cursor: String? = null
        var complete = false
        while (all.size < MAX_CONVERSATIONS) {
            val page = dmApi.listConversations(cursor, PAGE_CONVERSATIONS)
            all += page.conversations
            cursor = page.nextCursor
            if (cursor == null) {
                complete = true
                break
            }
        }
        db.withTransaction {
            dao.upsertConversations(all.map { it.toEntity(account.key) })
            if (complete) {
                dao.deleteConversationsExcept(account.key, all.map { it.id }.ifEmpty { listOf("") })
                dao.deleteOrphanMessages(account.key)
            }
        }
        pruneOversizedThreads(account, openConversationId)
    }

    /** Initial load or delta, whichever the thread needs. Safe to call concurrently. */
    suspend fun syncThread(account: Account, conversationId: String) = lockFor(account, conversationId).withLock {
        val cursor = dao.cursor(account.key, conversationId)
        if (cursor?.headCursor == null) initialLoad(account, conversationId) else drainDelta(account, conversationId, cursor)
    }

    /** One page further back in history. Returns false once the start of the thread is cached. */
    suspend fun loadOlder(account: Account, conversationId: String): Boolean = lockFor(account, conversationId).withLock {
        val cursor = dao.cursor(account.key, conversationId) ?: return@withLock true
        if (cursor.reachedStart || cursor.olderCursor == null) return@withLock false
        val page = dmApi.listMessages(conversationId, "before", cursor.olderCursor, PAGE_MESSAGES)
        db.withTransaction {
            dao.upsertMessages(page.messages.map { it.toEntity(account.key) })
            dao.upsertCursor(cursor.copy(olderCursor = page.nextCursor, reachedStart = page.nextCursor == null))
        }
        page.nextCursor != null
    }

    private suspend fun initialLoad(account: Account, conversationId: String) {
        val page = dmApi.listMessages(conversationId, "before", null, PAGE_MESSAGES)
        db.withTransaction {
            dao.upsertMessages(page.messages.map { it.toEntity(account.key) })
            dao.upsertCursor(
                ThreadCursorEntity(
                    accountKey = account.key,
                    conversationId = conversationId,
                    headCursor = page.headCursor,
                    olderCursor = page.nextCursor,
                    reachedStart = page.nextCursor == null,
                ),
            )
            page.messages.mapNotNull { it.clientMessageId }.forEach { dao.deleteOutgoing(account.key, it) }
        }
    }

    private suspend fun drainDelta(account: Account, conversationId: String, start: ThreadCursorEntity) {
        var cursor = start
        repeat(MAX_DELTA_PAGES) {
            val page = dmApi.listMessages(conversationId, "after", cursor.headCursor, DELTA_PAGE)
            cursor = cursor.copy(headCursor = page.headCursor ?: cursor.headCursor)
            db.withTransaction {
                dao.upsertMessages(page.messages.map { it.toEntity(account.key) })
                dao.upsertCursor(cursor)
                page.messages.mapNotNull { it.clientMessageId }.forEach { dao.deleteOutgoing(account.key, it) }
            }
            if (page.messages.isEmpty() || page.nextCursor == null) return
        }
    }

    suspend fun markRead(account: Account, conversationId: String) {
        val conversation = dao.conversation(account.key, conversationId) ?: return
        val newest = dao.newestMessage(account.key, conversationId) ?: return
        if (newest.id == conversation.lastReadMessageId && conversation.unreadCount == 0) return
        dmApi.markRead(conversationId, newest.id)
        dao.markConversationRead(account.key, conversationId, newest.id)
    }

    suspend fun applyPeerRead(account: Account, conversationId: String, messageId: String) {
        dao.setPeerLastRead(account.key, conversationId, messageId)
    }

    /** Queue a text message. The `clientMessageId` is fixed now and reused by every retry. */
    suspend fun send(account: Account, conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val clientMessageId = UUID.randomUUID().toString()
        dao.upsertOutgoing(
            OutgoingMessageEntity(
                accountKey = account.key,
                clientMessageId = clientMessageId,
                conversationId = conversationId,
                body = body.take(MAX_BODY),
                state = OutgoingMessageEntity.STATE_SENDING,
                error = null,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        enqueueSend(account.key, clientMessageId)
    }

    suspend fun retry(account: Account, clientMessageId: String) {
        dao.outgoing(account.key, clientMessageId) ?: return
        dao.setOutgoingState(account.key, clientMessageId, OutgoingMessageEntity.STATE_SENDING, null)
        enqueueSend(account.key, clientMessageId)
    }

    suspend fun discard(account: Account, clientMessageId: String) = dao.deleteOutgoing(account.key, clientMessageId)

    /** Called by the send worker once the server confirmed the message. */
    suspend fun confirmSent(accountKey: String, clientMessageId: String, message: DmMessageDto) {
        db.withTransaction {
            dao.upsertMessages(listOf(message.toEntity(accountKey)))
            dao.deleteOutgoing(accountKey, clientMessageId)
        }
    }

    suspend fun outgoingRow(accountKey: String, clientMessageId: String) = dao.outgoing(accountKey, clientMessageId)

    suspend fun markOutgoingFailed(accountKey: String, clientMessageId: String, error: String?) =
        dao.setOutgoingState(accountKey, clientMessageId, OutgoingMessageEntity.STATE_FAILED, error)

    /** Keep the cache bounded: a thread past the cap is dropped and reloaded fresh on next open. */
    private suspend fun pruneOversizedThreads(account: Account, openConversationId: String?) {
        dao.oversizedThreads(account.key, MAX_CACHED_MESSAGES_PER_THREAD)
            .filter { it != openConversationId }
            .forEach { id ->
                db.withTransaction {
                    dao.deleteThreadMessages(account.key, id)
                    dao.deleteCursor(account.key, id)
                }
            }
    }

    private fun lockFor(account: Account, conversationId: String): Mutex =
        threadLocks.getOrPut("${account.key}/$conversationId") { Mutex() }

    companion object {
        const val PAGE_MESSAGES = 50
        const val DELTA_PAGE = 100
        const val MAX_DELTA_PAGES = 20
        const val PAGE_CONVERSATIONS = 50
        const val MAX_CONVERSATIONS = 300
        const val MAX_CACHED_MESSAGES_PER_THREAD = 600
        const val MAX_BODY = 4096
    }
}

private val blocksSerializer = ListSerializer(JsonElement.serializer())

fun DmConversationDto.toEntity(accountKey: String) = ConversationEntity(
    accountKey = accountKey,
    id = id,
    isGroup = group != null,
    isBot = peer?.isBot == true,
    title = group?.title ?: peer?.displayName ?: "",
    peerUserId = peer?.userId,
    memberCount = group?.memberCount ?: 2,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    lastMessageSenderId = lastMessageSenderId,
    lastMessageSenderName = group?.lastMessageSenderName,
    unreadCount = unreadCount,
    lastReadMessageId = lastReadMessageId,
    peerLastReadMessageId = peerLastReadMessageId,
    mutedUntil = mutedUntil,
    updatedAt = updatedAt,
)

fun DmMessageDto.toEntity(accountKey: String) = MessageEntity(
    accountKey = accountKey,
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    senderName = sender?.displayName,
    body = body,
    messageType = messageType,
    systemTitle = systemTitle,
    systemSeverity = systemSeverity,
    systemBlocksJson = systemBlocks?.takeIf { it.isNotEmpty() }?.let { ApiJson.encodeToString(blocksSerializer, it) },
    mediaFileName = media?.fileName,
    clientMessageId = clientMessageId,
    readAt = readAt,
    deletedAt = deletedAt,
    createdAt = createdAt,
)
