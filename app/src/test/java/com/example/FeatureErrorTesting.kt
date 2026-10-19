package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import com.example.data.model.DesktopNodeConfig
import com.example.data.notifications.FollowUpNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureErrorTesting {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var cipher: TestVaultCipher
    private val diskNames = mutableListOf<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cipher = TestVaultCipher()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        EncryptionManager.initialize(context)
        EncryptionManager.lock()
    }
    @After fun cleanup() {
        database.close()
        diskNames.forEach(context::deleteDatabase)
        EncryptionManager.lock()
    }

    @Test fun productionVaultRejectsAllReadsAndWritesWhileLocked() {
        val previousGeneration = EncryptionManager.accessGeneration
        EncryptionManager.lock()
        assertTrue(EncryptionManager.accessGeneration > previousGeneration)
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("private") }
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt("ENC:private") }
        assertFalse(EncryptionManager.isUnlocked.value)
    }

    @Test fun arbitraryApplicationPinsNeverAuthenticate() {
        val auth = BiometricAuthManager(context)
        listOf("", "1234", "0000", "123456", "abcdef").forEach { assertFalse(auth.unlockWithPin(it)) }
        assertFalse(auth.isUnlocked.value)
    }

    @Test fun authenticatedCodecRejectsPlaintextTamperAndDifferentKeys() {
        val encrypted = cipher.encrypt("Synthetic protected note")
        assertTrue(encrypted.startsWith(VaultCodec.PREFIX))
        assertEquals("Synthetic protected note", cipher.decrypt(encrypted))
        assertNotEquals(encrypted, cipher.encrypt("Synthetic protected note"))
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("ENC:unsafe") }
        val mutated = encrypted.dropLast(5) + "AAAA="
        assertThrows(Exception::class.java) { cipher.decrypt(mutated) }
        assertThrows(Exception::class.java) { TestVaultCipher().decrypt(encrypted) }
    }

    @Test fun allSensitiveMetadataAndContentsStayEncryptedAtRest() = runBlocking {
        val row = EncryptedChatMessage("m", "s", "user", cipher.encrypt("Synthetic private body"),
            attachmentName = "Synthetic Resident.pdf", deliveryError = "Synthetic confidential error")
        database.chatDao().insertMessage(VaultRecords.encode(row, cipher))
        val raw = database.chatDao().messageById("m")!!
        assertTrue(raw.encryptedContent.startsWith(VaultCodec.PREFIX))
        assertTrue(raw.attachmentName!!.startsWith(VaultCodec.PREFIX))
        assertTrue(raw.deliveryError!!.startsWith(VaultCodec.PREFIX))
        assertEquals("Synthetic Resident.pdf", VaultRecords.decode(raw, cipher).attachmentName)
        assertEquals("Synthetic private body", cipher.decrypt(raw.encryptedContent))
    }

    @Test fun pairingStorageIsEncryptedAndCannotLoadWhenLocked() {
        val store = SecureConfigStore(context, cipher)
        val config = DesktopNodeConfig(hostIp = "desktop.example.invalid", pairKey = "synthetic-device-secret",
            certificateDer = "synthetic-certificate", certificateFingerprint = "synthetic-fingerprint",
            simulatedNodeEnabled = false, scheme = "https")
        store.save(config)
        val file = java.io.File(context.noBackupFilesDir, "private-ai-gateway-v2.bin")
        assertFalse(file.readText().contains(config.pairKey))
        assertEquals(config.pairKey, store.load()!!.pairKey)
        cipher.available = false
        assertThrows(VaultUnavailableException::class.java) { store.load() }
        cipher.available = true
        assertThrows(IllegalArgumentException::class.java) { store.save(config.copy(scheme = "http")) }
        store.clear()
        assertNull(store.load())
    }

    @Test fun restrictedNotificationOptInCannotPersistFullCredentialsWhileLocked() {
        val store = RestrictedNotificationStore(context)
        assertThrows(IllegalStateException::class.java) { store.save(DesktopNodeConfig(
            pairKey = "a".repeat(43), notificationKey = "b".repeat(43)), true) }
        assertFalse(store.isEnabled())
    }

    @Test fun reminderReceiptIsSentOnlyAfterItsGenericNoticeWasAccepted() = runBlocking {
        val receipts = mutableListOf<String>()
        val json = org.json.JSONObject().put("notifications", org.json.JSONArray()
            .put(org.json.JSONObject().put("id", "acknowledged").put("status", "acknowledged"))
            .put(org.json.JSONObject().put("id", "displayed").put("status", "pending"))
            .put(org.json.JSONObject().put("id", "permission-denied").put("status", "pending")))
        assertFalse(FollowUpNotifications.deliverPending(json, { it != "permission-denied" }, {
            receipts += it; true
        }))
        assertEquals(listOf("displayed"), receipts)
    }

    @Test fun attachmentReadsHonorDeclaredAndActualBounds() {
        assertEquals(3, AttachmentUtils.readBounded(ByteArrayInputStream(byteArrayOf(1,2,3)), 3).size)
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentUtils.readBounded(ByteArrayInputStream(byteArrayOf(1,2,3,4)), 3)
        }
        assertNull(AttachmentUtils.base64ToBitmap("A".repeat(12 * 1024 * 1024)))
    }

    @Test fun replyReceiptIsAtomicIdempotentAndBoundToOriginalChat() = runBlocking {
        val original = EncryptedChatMessage("request", "chat", "user", cipher.encrypt("request"),
            status = "QUEUED_OFFLINE")
        database.chatDao().insertMessage(VaultRecords.encode(original, cipher))
        val reply = VaultRecords.encode(EncryptedChatMessage("reply-request", "chat", "assistant",
            cipher.encrypt("reply"), status = "SYNCED_DESKTOP"), cipher)
        database.chatDao().acknowledgeMessageWithReply("request", reply)
        database.chatDao().acknowledgeMessageWithReply("request", reply)
        assertEquals(2, database.chatDao().allMessagesForMigration().size)
        assertEquals("SYNCED_DESKTOP", database.chatDao().messageById("request")!!.status)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            database.chatDao().acknowledgeMessageWithReply("request", reply.copy(sessionId = "another-chat"))
        } }
        assertEquals(2, database.chatDao().allMessagesForMigration().size)
    }

    @Test fun refreshingDesktopHistoryPreservesUnsentAndReviewRequiredMessages() = runBlocking {
        val pending = EncryptedChatMessage("p", "chat", "user", cipher.encrypt("pending"), status = "QUEUED_OFFLINE")
        val review = pending.copy(id = "r", requestId = "r", status = "REVIEW_REQUIRED")
        val oldRemote = pending.copy(id = "old", requestId = "old", status = "SYNCED_DESKTOP")
        database.chatDao().insertMessages(listOf(pending, review, oldRemote).map { VaultRecords.encode(it, cipher) })
        val remote = VaultRecords.encode(oldRemote.copy(id = "new", requestId = "new"), cipher)
        database.chatDao().replaceSyncedHistory("chat", listOf(remote))
        assertEquals(setOf("p", "r", "new"), database.chatDao().allMessagesForMigration().map { it.id }.toSet())
    }

    @Test fun notificationSyncDoesNotUndoLocalOrServerAcknowledgments() = runBlocking {
        val alert = DesktopNotification("alert", "Synthetic title", "Synthetic text", timestamp = 1)
        database.taskDao().insertNotification(VaultRecords.encode(alert.copy(isRead = true, isAcknowledged = true), cipher))
        database.taskDao().mergeNotificationsPreservingLocalState(listOf(VaultRecords.encode(alert, cipher)))
        assertTrue(database.taskDao().notificationById("alert")!!.isAcknowledged)
        database.taskDao().insertNotification(VaultRecords.encode(alert.copy(id = "other"), cipher))
        database.taskDao().mergeNotificationsPreservingLocalState(listOf(VaultRecords.encode(
            alert.copy(id = "other", isAcknowledged = true), cipher)))
        assertTrue(database.taskDao().notificationById("other")!!.isAcknowledged)
    }

    @Test fun legacyContentMigrationPreservesRecordsAndRequiresReviewForUnpairedQueue() = runBlocking {
        database.chatDao().insertSession(EncryptedChatSession("legacy-chat", "Old title"))
        database.chatDao().insertMessage(EncryptedChatMessage("legacy-request", "legacy-chat", "user",
            "ENC:Synthetic old body", status = "QUEUED_OFFLINE", attachmentName = "Old evidence.pdf"))
        database.documentDao().insertDocument(EncryptedDocument("old-doc", "Old report", "TEXT",
            "ENC:Synthetic old report", "checksum", 20))
        val outcome = database.migrateLegacyContent(cipher)
        assertEquals(3, outcome.migrated)
        assertEquals(2, outcome.legacyPlaintext)
        val message = database.chatDao().messageById("legacy-request")!!
        assertEquals("REVIEW_REQUIRED", message.status)
        assertNull(message.desktopFingerprint)
        assertTrue(message.requireLocal)
        assertTrue(message.requireHipaa)
        assertEquals("Synthetic old body", cipher.decrypt(message.encryptedContent))
        assertEquals("Old evidence.pdf", VaultRecords.decode(message, cipher).attachmentName)
        assertEquals(0, database.migrateLegacyContent(cipher).migrated)
    }

    @Test fun failedLegacyMigrationRollsBackWithoutDiscardingOldData() = runBlocking {
        database.chatDao().insertSession(EncryptedChatSession("s", "Preserved title"))
        database.chatDao().insertMessage(EncryptedChatMessage("a", "s", "user", "ENC:Good old body", timestamp = 1))
        database.chatDao().insertMessage(EncryptedChatMessage("b", "s", "user", "corrupt", timestamp = 2))
        assertThrows(Exception::class.java) { runBlocking { database.migrateLegacyContent(cipher) } }
        assertEquals("Preserved title", database.chatDao().allSessionsForMigration().single().title)
        assertEquals(0, database.chatDao().allSessionsForMigration().single().storageVersion)
        assertEquals("ENC:Good old body", database.chatDao().messageById("a")!!.encryptedContent)
    }

    @Test fun retiredSyntheticClinicalRecordsRemainPreservedButCannotLookCurrent() = runBlocking {
        database.taskDao().insertTask(DesktopTask("task-seed-1", "Synthetic completed work", "COMPLETED",
            resultSummary = "Original synthetic result", requiresApproval = true))
        database.taskDao().insertNotification(DesktopNotification("notif-1", "Synthetic critical finding",
            "Original synthetic message", severity = "CRITICAL", requiresApproval = true))
        database.migrateLegacyContent(cipher)
        val task = VaultRecords.decode(database.taskDao().allTasksForMigration().single(), cipher)
        val notification = VaultRecords.decode(database.taskDao().allNotificationsForMigration().single(), cipher)
        assertTrue(task.title.startsWith("Legacy demo — unverified synthetic"))
        assertEquals("REVIEW_REQUIRED", task.status)
        assertFalse(task.requiresApproval)
        assertTrue(task.resultSummary!!.contains("Original synthetic result"))
        assertEquals("INFO", notification.severity)
        assertEquals("LEGACY_UNVERIFIED", notification.category)
        assertFalse(notification.requiresApproval)
        assertTrue(notification.message.contains("Original synthetic message"))
        assertTrue(notification.message.contains("Original severity: CRITICAL"))
    }

    @Test fun actualRetiredAesCiphertextAndAttachmentsMigrateIntoNewAuthenticatedFormat() = runBlocking {
        val spec = javax.crypto.spec.PBEKeySpec("PrivateAI-MasterSecret-Key-2026".toCharArray(),
            "PrivateAI_E2EE_LocalVault_Salt_v1".toByteArray(), 10000, 256)
        val key = javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded, "AES")
        fun retired(value: String): String {
            val encryptor = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            encryptor.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return android.util.Base64.encodeToString(encryptor.iv + encryptor.doFinal(value.toByteArray()), android.util.Base64.NO_WRAP)
        }
        database.chatDao().insertMessage(EncryptedChatMessage("old-aes", "old-chat", "user",
            retired("Retired encrypted synthetic note"), attachmentDataEncrypted = retired("c3ludGhldGlj")))
        val result = database.migrateLegacyContent(cipher)
        assertEquals(1, result.migrated)
        assertEquals(0, result.legacyPlaintext)
        val saved = database.chatDao().messageById("old-aes")!!
        assertEquals("Retired encrypted synthetic note", cipher.decrypt(saved.encryptedContent))
        assertEquals("c3ludGhldGlj", cipher.decrypt(saved.attachmentDataEncrypted!!))
    }

    @Test fun unsupportedHistoricalSchemaFailsAndPreservesItsDatabase() = runBlocking {
        val disk = openHistoricalDatabase(1)
        val name = diskNames.last()
        try {
            assertThrows(IllegalStateException::class.java) { runBlocking { disk.chatDao().allSessionsForMigration() } }
        } finally { disk.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(1, old.version)
            old.rawQuery("SELECT title FROM chat_sessions", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("Old v1 title", cursor.getString(0))
            }
        }
    }

    @Test fun v3SchemaUpgradePreservesRowsAndAddsOutboxWithoutWiping() = runBlocking {
        val disk = openHistoricalDatabase(3)
        try {
            assertEquals("old-v3", disk.chatDao().allSessionsForMigration().single().id)
            assertEquals("Old v3 title", disk.chatDao().allSessionsForMigration().single().title)
            assertEquals(0, disk.chatDao().allSessionsForMigration().single().storageVersion)
            assertTrue(disk.pendingOperationDao().pending("fingerprint").isEmpty())
        } finally { disk.close() }
    }

    @Test fun v2SchemaUpgradePreservesOldNotificationTypeAndTask() = runBlocking {
        val disk = openHistoricalDatabase(2)
        try {
            assertEquals("Old task", disk.taskDao().allTasksForMigration().single().title)
            assertEquals("SECURITY_EVENT", disk.taskDao().allNotificationsForMigration().single().category)
            assertEquals("Old alert", disk.taskDao().allNotificationsForMigration().single().message)
            assertFalse(disk.taskDao().allNotificationsForMigration().single().isAcknowledged)
        } finally { disk.close() }
    }

    private fun openHistoricalDatabase(version: Int): AppDatabase {
        val name = "migration-${UUID.randomUUID()}.db"
        diskNames += name
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("""CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,lastMessagePreviewEncrypted TEXT NOT NULL,
                lastMessageTimestamp INTEGER NOT NULL,modelUsed TEXT NOT NULL,isSynced INTEGER NOT NULL)""")
            old.execSQL("""CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,
                sender TEXT NOT NULL,encryptedContent TEXT NOT NULL,timestamp INTEGER NOT NULL,status TEXT NOT NULL,
                promptTokens INTEGER NOT NULL,completionTokens INTEGER NOT NULL,modelName TEXT NOT NULL,
                attachmentName TEXT,attachmentType TEXT,attachmentDataEncrypted TEXT,attachmentSize INTEGER NOT NULL)""")
            old.execSQL("""CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY,fileName TEXT NOT NULL,fileType TEXT NOT NULL,
                encryptedContent TEXT NOT NULL,checksumSha256 TEXT NOT NULL,fileSizeBytes INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,syncedWithDesktop INTEGER NOT NULL,tags TEXT NOT NULL)""")
            val extra = if (version == 3) ",category TEXT NOT NULL,requiresApproval INTEGER NOT NULL,approvalPrompt TEXT" else ""
            old.execSQL("""CREATE TABLE desktop_tasks (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,status TEXT NOT NULL,
                progress REAL NOT NULL,modelName TEXT NOT NULL,startedAt INTEGER NOT NULL,completedAt INTEGER,resultSummary TEXT$extra)""")
            if (version == 2) old.execSQL("""CREATE TABLE desktop_notifications (id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,message TEXT NOT NULL,timestamp INTEGER NOT NULL,isRead INTEGER NOT NULL,type TEXT NOT NULL)""")
            else old.execSQL("""CREATE TABLE desktop_notifications (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,
                message TEXT NOT NULL,timestamp INTEGER NOT NULL,isRead INTEGER NOT NULL,isAcknowledged INTEGER NOT NULL,
                severity TEXT NOT NULL,category TEXT NOT NULL,requiresApproval INTEGER NOT NULL,assignedTo TEXT,relatedAction TEXT)""")
            old.execSQL("INSERT INTO chat_sessions VALUES ('old-v$version','Old v$version title',1,'',1,'Old model',0)")
            if (version == 2) {
                old.execSQL("INSERT INTO desktop_tasks VALUES ('t','Old task','COMPLETED',1.0,'Old model',1,2,'Old result')")
                old.execSQL("INSERT INTO desktop_notifications VALUES ('n','Old alert title','Old alert',1,1,'SECURITY_EVENT')")
            }
            old.version = version
        }
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).allowMainThreadQueries().build()
    }
}
