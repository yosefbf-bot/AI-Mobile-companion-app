package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class EncryptedChatSession(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessagePreviewEncrypted: String = "",
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val modelUsed: String = "Llama-3-70B-Instruct (Local)",
    val isSynced: Boolean = true
)

@Entity(tableName = "chat_messages")
data class EncryptedChatMessage(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val sender: String, // "user" or "assistant"
    val encryptedContent: String, // AES-256-GCM ciphertext
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SYNCED_DESKTOP", // "QUEUED_OFFLINE", "SYNCED_DESKTOP", "PENDING_INFERENCE"
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val modelName: String = "Private-AI Local",
    val attachmentName: String? = null,
    val attachmentType: String? = null, // "IMAGE", "DOCUMENT", "CODE"
    val attachmentDataEncrypted: String? = null, // AES-256-GCM encrypted Base64
    val attachmentSize: Long = 0L
)
