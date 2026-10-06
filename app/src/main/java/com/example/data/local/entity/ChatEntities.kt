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
    val modelUsed: String = "Unavailable",
    val isSynced: Boolean = false,
    val desktopChatId: String? = null,
    val desktopRevision: Long = 0,
    val storageVersion: Int = 0
)

@Entity(tableName = "chat_messages")
data class EncryptedChatMessage(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val sender: String, // "user" or "assistant"
    val encryptedContent: String, // AES-256-GCM ciphertext
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING_INFERENCE", // A desktop acknowledgment is required for SYNCED_DESKTOP.
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val modelName: String = "Unavailable",
    val attachmentName: String? = null,
    val attachmentType: String? = null, // "IMAGE", "DOCUMENT", "CODE"
    val attachmentDataEncrypted: String? = null, // AES-256-GCM encrypted Base64
    val attachmentSize: Long = 0L,
    val requestId: String = id,
    val deliveryError: String? = null,
    val attemptedAt: Long? = null,
    val desktopMessageId: String? = null,
    val desktopFingerprint: String? = null,
    val requireLocal: Boolean = true,
    val requireHipaa: Boolean = true,
    val storageVersion: Int = 0
)
