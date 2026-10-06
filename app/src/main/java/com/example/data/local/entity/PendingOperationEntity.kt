package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Encrypted durable outbox. The desktop request ID stays stable on retries. */
@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey val id: String,
    val operation: String,
    val encryptedPayload: String,
    val desktopFingerprint: String,
    val createdAt: Long,
    val status: String = "PENDING",
    val attemptedAt: Long? = null,
    val serverId: String? = null
)
