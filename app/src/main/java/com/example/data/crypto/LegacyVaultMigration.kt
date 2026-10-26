package com.example.data.crypto

import android.util.Base64
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class MigrationOutcome(val migrated: Int, val legacyPlaintext: Int)

/**
 * Read-only compatibility decoder for the retired format. Its old public constant
 * is never used to encrypt new data or unlock the application.
 */
object LegacyVaultMigration {
    private const val MAX_PAYLOAD = 34 * 1024 * 1024
    private fun decodeRetired(value: String): String {
        require(value.length <= MAX_PAYLOAD) { "Legacy vault payload exceeds its size limit." }
        if (value.isEmpty()) return ""
        if (value.startsWith("ENC:")) return value.removePrefix("ENC:")
        val spec = PBEKeySpec("PrivateAI-MasterSecret-Key-2026".toCharArray(),
            "PrivateAI_E2EE_LocalVault_Salt_v1".toByteArray(Charsets.UTF_8), 10000, 256)
        val keyBytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size >= 28) { "Invalid legacy vault payload. Original records were preserved." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } finally { keyBytes.fill(0) }
    }

    suspend fun migrate(database: AppDatabase, cipher: VaultCipher): MigrationOutcome {
        // Replaced plaintext metadata must not remain in deleted SQLite cells or old WAL frames.
        database.openHelper.writableDatabase.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        val result = database.withTransaction {
        var migrated = 0
        var plaintext = 0
        val knownDemo = setOf("session-sec-1", "session-sec-2", "msg-1", "msg-2", "msg-3", "msg-4",
            "doc-1", "doc-2", "task-seed-1", "task-seed-2", "notif-1", "notif-2")
        fun label(id: String) = if (id in knownDemo)
            "Legacy demo — unverified synthetic; no desktop work performed"
            else "Legacy — review source before use"
        fun body(value: String): String {
            if (value.startsWith(VaultCodec.PREFIX) || value.startsWith(PolicyVaultCodec.PREFIX)) {
                cipher.decrypt(value) // Verify rather than trusting a format marker.
                return value
            }
            if (value.startsWith("ENC:")) plaintext++
            return cipher.encrypt(decodeRetired(value))
        }
        database.chatDao().legacySessions().forEach { row ->
            database.chatDao().insertSession(VaultRecords.encode(row.copy(
                title = "${label(row.id)}: ${row.title}",
                modelUsed = "Legacy unverified: ${row.modelUsed}", isSynced = false,
                lastMessagePreviewEncrypted = body(row.lastMessagePreviewEncrypted)), cipher))
            migrated++
        }
        database.chatDao().legacyMessageIds().forEach { id ->
            val row = database.chatDao().messageById(id) ?: error("Legacy message changed during migration.")
            database.chatDao().insertMessage(VaultRecords.encode(row.copy(
                encryptedContent = body(row.encryptedContent),
                attachmentDataEncrypted = row.attachmentDataEncrypted?.let(::body),
                // A queue from the retired app has no authenticated destination.
                status = if (row.status == "QUEUED_OFFLINE" || row.status == "PENDING_INFERENCE")
                    "REVIEW_REQUIRED" else "LEGACY_UNVERIFIED",
                deliveryError = "${label(row.id)}. Original delivery status: ${row.status}. ${row.deliveryError.orEmpty()}",
                requestId = row.requestId.ifBlank { row.id }
            ), cipher))
            migrated++
        }
        database.documentDao().legacyIds().forEach { id ->
            val row = database.documentDao().hydratedById(id) ?: error("Legacy document changed during migration.")
            database.documentDao().insertDocument(VaultRecords.encode(row.copy(
                fileName = "${label(row.id)}: ${row.fileName}", syncedWithDesktop = false,
                encryptedContent = body(row.encryptedContent)), cipher))
            migrated++
        }
        database.taskDao().legacyTasks().forEach { row ->
            database.taskDao().insertTask(VaultRecords.encode(row.copy(
                title = "${label(row.id)}: ${row.title}", status = "REVIEW_REQUIRED", category = "LEGACY_UNVERIFIED",
                requiresApproval = false,
                resultSummary = "${label(row.id)}. Original status: ${row.status}; category: ${row.category}; requiresApproval: ${row.requiresApproval}.\n${row.resultSummary.orEmpty()}"
            ), cipher)); migrated++
        }
        database.taskDao().legacyNotifications().forEach { row ->
            database.taskDao().insertNotification(VaultRecords.encode(row.copy(
                title = "${label(row.id)}: ${row.title}", severity = "INFO", category = "LEGACY_UNVERIFIED",
                requiresApproval = false,
                message = "${label(row.id)}. Original severity: ${row.severity}; category: ${row.category}; requiresApproval: ${row.requiresApproval}.\n${row.message}"
            ), cipher)); migrated++
        }
        MigrationOutcome(migrated, plaintext)
        }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "The protected vault upgrade could not finish its local storage checkpoint. Original records were preserved."
            }
        }
        return result
    }
}
