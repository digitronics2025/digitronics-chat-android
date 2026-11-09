package com.digitronics.chat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ── Conversations ────────────────────────────────────────────────────────
    @Query("SELECT * FROM conversations WHERE accountKey = :account ORDER BY lastMessageAt IS NULL, lastMessageAt DESC, updatedAt DESC")
    fun observeConversations(account: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE accountKey = :account AND id = :id")
    fun observeConversation(account: String, id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE accountKey = :account AND id = :id")
    suspend fun conversation(account: String, id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversations(rows: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE accountKey = :account AND id NOT IN (:keep)")
    suspend fun deleteConversationsExcept(account: String, keep: List<String>)

    @Query("UPDATE conversations SET unreadCount = 0, lastReadMessageId = :messageId WHERE accountKey = :account AND id = :id")
    suspend fun markConversationRead(account: String, id: String, messageId: String)

    @Query("UPDATE conversations SET peerLastReadMessageId = :messageId WHERE accountKey = :account AND id = :id")
    suspend fun setPeerLastRead(account: String, id: String, messageId: String)

    // ── Messages ─────────────────────────────────────────────────────────────
    @Query("SELECT * FROM messages WHERE accountKey = :account AND conversationId = :conversationId ORDER BY createdAt DESC, id DESC")
    fun observeMessages(account: String, conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE accountKey = :account AND id = :id")
    suspend fun message(account: String, id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE accountKey = :account AND conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun newestMessage(account: String, conversationId: String): MessageEntity?

    @Upsert
    suspend fun upsertMessages(rows: List<MessageEntity>)

    @Query("SELECT COUNT(*) FROM messages WHERE accountKey = :account AND conversationId = :conversationId")
    suspend fun messageCount(account: String, conversationId: String): Int

    @Query("SELECT conversationId FROM messages WHERE accountKey = :account GROUP BY conversationId HAVING COUNT(*) > :limit")
    suspend fun oversizedThreads(account: String, limit: Int): List<String>

    @Query("DELETE FROM messages WHERE accountKey = :account AND conversationId = :conversationId")
    suspend fun deleteThreadMessages(account: String, conversationId: String)

    @Query("DELETE FROM messages WHERE accountKey = :account AND conversationId NOT IN (SELECT id FROM conversations WHERE accountKey = :account)")
    suspend fun deleteOrphanMessages(account: String)

    // ── Cursors ──────────────────────────────────────────────────────────────
    @Query("SELECT * FROM thread_cursors WHERE accountKey = :account AND conversationId = :conversationId")
    suspend fun cursor(account: String, conversationId: String): ThreadCursorEntity?

    @Query("SELECT * FROM thread_cursors WHERE accountKey = :account AND conversationId = :conversationId")
    fun observeCursor(account: String, conversationId: String): Flow<ThreadCursorEntity?>

    @Upsert
    suspend fun upsertCursor(row: ThreadCursorEntity)

    @Query("DELETE FROM thread_cursors WHERE accountKey = :account AND conversationId = :conversationId")
    suspend fun deleteCursor(account: String, conversationId: String)

    // ── Outgoing ─────────────────────────────────────────────────────────────
    @Query("SELECT * FROM outgoing WHERE accountKey = :account AND conversationId = :conversationId ORDER BY createdAtMillis DESC")
    fun observeOutgoing(account: String, conversationId: String): Flow<List<OutgoingMessageEntity>>

    @Query("SELECT * FROM outgoing WHERE accountKey = :account AND clientMessageId = :clientMessageId")
    suspend fun outgoing(account: String, clientMessageId: String): OutgoingMessageEntity?

    @Upsert
    suspend fun upsertOutgoing(row: OutgoingMessageEntity)

    @Query("UPDATE outgoing SET state = :state, error = :error WHERE accountKey = :account AND clientMessageId = :clientMessageId")
    suspend fun setOutgoingState(account: String, clientMessageId: String, state: String, error: String?)

    @Query("DELETE FROM outgoing WHERE accountKey = :account AND clientMessageId = :clientMessageId")
    suspend fun deleteOutgoing(account: String, clientMessageId: String)
}
