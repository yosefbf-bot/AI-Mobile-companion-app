package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local only. No route/title/content is stored in plaintext or admitted to desktop sync. */
@Entity(tableName = "local_drafts")
data class LocalDraft(
    @PrimaryKey val id: String,
    val revision: Long,
    val deleted: Boolean,
    val encryptedFields: String?,
    val evidenceId: String?,
    val updatedAt: Long,
    val deleteGeneration: Long = 0
)

/** Immutable ciphertext; read in bounded slices to avoid Android CursorWindow row limits. */
@Entity(tableName = "local_draft_evidence")
data class LocalDraftEvidence(@PrimaryKey val id: String, val encryptedPayload: String, val ciphertextSha256: String)
