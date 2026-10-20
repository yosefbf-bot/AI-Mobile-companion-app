package com.example.data.crypto

import com.example.data.local.AppDatabase

/** Bounded scalar queries, never plaintext row copies, before first v2 key creation. */
internal object VaultStorageInventory {
    private val columns = linkedMapOf(
        "chat_sessions" to listOf("title", "modelUsed", "lastMessagePreviewEncrypted"),
        "chat_messages" to listOf("encryptedContent", "modelName", "attachmentName", "attachmentDataEncrypted", "deliveryError"),
        "documents" to listOf("fileName", "tags", "encryptedContent"),
        "desktop_tasks" to listOf("title", "modelName", "resultSummary", "approvalPrompt"),
        "desktop_notifications" to listOf("title", "message", "assignedTo", "relatedAction")
    )
    fun hasProtectedRows(database: AppDatabase): Boolean {
        val sql = database.openHelper.readableDatabase
        for ((table, fields) in columns) {
            // Version-zero retired data can receive its first secure key, but a PAI2/3
            // body in that older envelope still proves an existing protected key is needed.
            val predicates = listOf("storageVersion != 0") + fields.map { "substr($it,1,5) IN ('PAI2:','PAI3:')" }
            sql.query("SELECT EXISTS(SELECT 1 FROM $table WHERE ${predicates.joinToString(" OR ")} LIMIT 1)").use {
                check(it.moveToFirst()); if (it.getInt(0) != 0) return true
            }
        }
        sql.query("SELECT EXISTS(SELECT 1 FROM local_draft_evidence LIMIT 1)").use {
            check(it.moveToFirst()); if (it.getInt(0) != 0) return true
        }
        sql.query("SELECT EXISTS(SELECT 1 FROM local_drafts LIMIT 1)").use {
            check(it.moveToFirst()); if (it.getInt(0) != 0) return true
        }
        sql.query("SELECT EXISTS(SELECT 1 FROM pending_operations LIMIT 1)").use {
            check(it.moveToFirst()); if (it.getInt(0) != 0) return true
        }
        return false
    }
}
