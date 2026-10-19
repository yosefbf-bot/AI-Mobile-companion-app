package com.example.data.crypto

import androidx.room.withTransaction
import com.example.data.local.AppDatabase

/** One row per transaction bounds memory, including encrypted evidence/audio payloads. */
internal object VaultPolicyMigration {
    suspend fun convert(database: AppDatabase, rewrite: (String) -> String, checkAccess: () -> Unit) {
        fun optional(value: String?) = value?.let(rewrite)
        val chat = database.chatDao()
        walk(database, chat::sessionsAfter, { it.id }, { row -> row.copy(title = rewrite(row.title),
            modelUsed = rewrite(row.modelUsed), lastMessagePreviewEncrypted = row.lastMessagePreviewEncrypted.let { if (it.isEmpty()) it else rewrite(it) }) }, chat::insertSession, checkAccess)
        walk(database, chat::messagesAfter, { it.id }, { row -> row.copy(encryptedContent = rewrite(row.encryptedContent),
            attachmentDataEncrypted = optional(row.attachmentDataEncrypted), attachmentName = optional(row.attachmentName),
            modelName = rewrite(row.modelName), deliveryError = optional(row.deliveryError)) }, chat::insertMessage, checkAccess)
        val documents = database.documentDao()
        walk(database, documents::afterForPolicy, { it.id }, { row -> row.copy(fileName = rewrite(row.fileName),
            tags = rewrite(row.tags), encryptedContent = rewrite(row.encryptedContent)) }, documents::insertDocument, checkAccess)
        val tasks = database.taskDao()
        walk(database, tasks::tasksAfter, { it.id }, { row -> row.copy(title = rewrite(row.title), modelName = rewrite(row.modelName),
            resultSummary = optional(row.resultSummary), approvalPrompt = optional(row.approvalPrompt)) }, tasks::insertTask, checkAccess)
        walk(database, tasks::notificationsAfter, { it.id }, { row -> row.copy(title = rewrite(row.title), message = rewrite(row.message),
            assignedTo = optional(row.assignedTo), relatedAction = optional(row.relatedAction)) }, tasks::insertNotification, checkAccess)
        val pending = database.pendingOperationDao()
        walk(database, pending::allAfterForPolicy, { it.id }, { row -> row.copy(encryptedPayload = rewrite(row.encryptedPayload)) }, pending::updateForPolicy, checkAccess)
        val drafts = database.localDraftDao()
        walk(database, drafts::afterForPolicy, { it.id }, { row -> row.copy(
            encryptedFields = optional(row.encryptedFields)) }, drafts::put, checkAccess)
        walk(database, { after, limit -> drafts.evidenceIdsAfter(after, limit).map { id -> drafts.readEvidence(id) ?: error("Draft evidence is unavailable.") } },
            { it.id }, { row -> rewrite(row.encryptedPayload).let { row.copy(encryptedPayload = it,
                ciphertextSha256 = EncryptionManager.computeSha256(it)) } },
            { row -> drafts.rewrapEvidence(row.id, row.encryptedPayload, row.ciphertextSha256) }, checkAccess)
        checkAccess()
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "Protected storage checkpoint is incomplete; retry after authentication." }
        }
        checkAccess()
    }

    private suspend fun <T> walk(database: AppDatabase, load: suspend (String?, Int) -> List<T>,
        id: (T) -> String, rewrite: (T) -> T, write: suspend (T) -> Unit, checkAccess: () -> Unit) {
        var after: String? = null
        while (true) {
            checkAccess()
            val next = database.withTransaction {
                checkAccess()
                val rows = load(after, 1)
                for (row in rows) {
                    val converted = rewrite(row)
                    checkAccess()
                    if (converted != row) write(converted)
                }
                checkAccess()
                rows.lastOrNull()?.let(id)
            } ?: return
            check(next != after) { "Protected migration cursor did not advance." }
            after = next
        }
    }
}
