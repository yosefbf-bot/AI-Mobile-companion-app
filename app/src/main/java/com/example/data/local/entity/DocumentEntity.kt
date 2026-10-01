package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class EncryptedDocument(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val fileType: String, // "MARKDOWN", "CODE", "RESEARCH_NOTES", "JSON", "REPORT"
    val encryptedContent: String, // AES-256-GCM ciphertext
    val checksumSha256: String,
    val fileSizeBytes: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedWithDesktop: Boolean = true,
    val tags: String = "Confidential, AI-Processed"
)
