package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun sessionsAfter(after: String?, limit: Int): List<EncryptedChatSession>
    @Query("SELECT * FROM chat_messages WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun messagesAfter(after: String?, limit: Int): List<EncryptedChatMessage>

    @Transaction
    suspend fun replaceSyncedHistory(sessionId: String, rows: List<EncryptedChatMessage>) {
        require(rows.all { it.sessionId == sessionId && it.status == "SYNCED_DESKTOP" })
        deleteAcknowledgedHistory(sessionId)
        insertMessages(rows)
    }

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND status = 'SYNCED_DESKTOP'")
    suspend fun deleteAcknowledgedHistory(sessionId: String)

    @Transaction
    suspend fun acknowledgeMessageWithReply(userId: String, reply: EncryptedChatMessage) {
        val user = messageById(userId) ?: error("The original queued request does not exist.")
        require(user.sender == "user" && reply.sender == "assistant" && reply.sessionId == user.sessionId)
        require(reply.id == "reply-$userId" && reply.status == "SYNCED_DESKTOP")
        insertMessage(reply)
        updateMessageStatus(userId, "SYNCED_DESKTOP")
    }

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun messageById(id: String): EncryptedChatMessage?

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun allMessagesForMigration(): List<EncryptedChatMessage>

    @Query("SELECT * FROM chat_sessions")
    suspend fun allSessionsForMigration(): List<EncryptedChatSession>

    @Query("SELECT * FROM chat_sessions ORDER BY lastMessageTimestamp DESC")
    fun getAllSessions(): Flow<List<EncryptedChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    fun getSessionById(sessionId: String): Flow<EncryptedChatSession?>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<EncryptedChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE status = 'QUEUED_OFFLINE' ORDER BY timestamp ASC")
    suspend fun getQueuedOfflineMessages(): List<EncryptedChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: EncryptedChatSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<EncryptedChatSession>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: EncryptedChatMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<EncryptedChatMessage>)

    @Update
    suspend fun updateMessage(message: EncryptedChatMessage)

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun clearAllSessions()

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
}
