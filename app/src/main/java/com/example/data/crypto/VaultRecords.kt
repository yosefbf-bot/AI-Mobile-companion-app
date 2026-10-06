package com.example.data.crypto

import com.example.data.local.entity.*

/** Preserve identifiers/status for queries; protect user and clinical metadata at rest. */
object VaultRecords {
    private fun enc(value: String?, cipher: VaultCipher) = value?.let(cipher::encrypt)
    private fun dec(value: String?, cipher: VaultCipher) = value?.let(cipher::decrypt)
    private fun check(version: Int) { require(version == 1) { "Vault data migration must complete before reading content." } }

    fun encode(row: EncryptedChatSession, cipher: VaultCipher): EncryptedChatSession = row.copy(
        title = cipher.encrypt(row.title), modelUsed = cipher.encrypt(row.modelUsed), storageVersion = 1)
    fun decode(row: EncryptedChatSession, cipher: VaultCipher): EncryptedChatSession {
        check(row.storageVersion)
        return row.copy(title = cipher.decrypt(row.title), modelUsed = cipher.decrypt(row.modelUsed), storageVersion = 0)
    }
    fun encode(row: EncryptedChatMessage, cipher: VaultCipher): EncryptedChatMessage = row.copy(
        attachmentName = enc(row.attachmentName, cipher), modelName = cipher.encrypt(row.modelName),
        deliveryError = enc(row.deliveryError, cipher), storageVersion = 1)
    fun decode(row: EncryptedChatMessage, cipher: VaultCipher): EncryptedChatMessage {
        check(row.storageVersion)
        return row.copy(attachmentName = dec(row.attachmentName, cipher), modelName = cipher.decrypt(row.modelName),
            deliveryError = dec(row.deliveryError, cipher), storageVersion = 0)
    }
    fun encode(row: EncryptedDocument, cipher: VaultCipher): EncryptedDocument = row.copy(
        fileName = cipher.encrypt(row.fileName), tags = cipher.encrypt(row.tags), storageVersion = 1)
    fun decode(row: EncryptedDocument, cipher: VaultCipher): EncryptedDocument {
        check(row.storageVersion)
        return row.copy(fileName = cipher.decrypt(row.fileName), tags = cipher.decrypt(row.tags), storageVersion = 0)
    }
    fun encode(row: DesktopTask, cipher: VaultCipher): DesktopTask = row.copy(
        title = cipher.encrypt(row.title), modelName = cipher.encrypt(row.modelName),
        resultSummary = enc(row.resultSummary, cipher), approvalPrompt = enc(row.approvalPrompt, cipher), storageVersion = 1)
    fun decode(row: DesktopTask, cipher: VaultCipher): DesktopTask {
        check(row.storageVersion)
        return row.copy(title = cipher.decrypt(row.title), modelName = cipher.decrypt(row.modelName),
            resultSummary = dec(row.resultSummary, cipher), approvalPrompt = dec(row.approvalPrompt, cipher), storageVersion = 0)
    }
    fun encode(row: DesktopNotification, cipher: VaultCipher): DesktopNotification = row.copy(
        title = cipher.encrypt(row.title), message = cipher.encrypt(row.message),
        assignedTo = enc(row.assignedTo, cipher), relatedAction = enc(row.relatedAction, cipher), storageVersion = 1)
    fun decode(row: DesktopNotification, cipher: VaultCipher): DesktopNotification {
        check(row.storageVersion)
        return row.copy(title = cipher.decrypt(row.title), message = cipher.decrypt(row.message),
            assignedTo = dec(row.assignedTo, cipher), relatedAction = dec(row.relatedAction, cipher), storageVersion = 0)
    }
}
