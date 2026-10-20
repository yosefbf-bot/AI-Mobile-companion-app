package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultPolicyMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<AppDatabase>()
    private val cipher = PolicyTestCipher()
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also { databases.add(it) }
    @After fun close() { databases.forEach { it.close() } }
    private suspend fun seed(db: AppDatabase) {
        for (id in listOf("", "SYNTHETIC-session", "\uE000", "\uD800\uDC00")) {
            db.chatDao().insertSession(VaultRecords.encode(EncryptedChatSession(id, "SYNTHETIC title $id", createdAt = 17,
                lastMessagePreviewEncrypted = if (id.isEmpty()) "" else cipher.encrypt("SYNTHETIC preview"),
                modelUsed = "SYNTHETIC model", desktopChatId = "desktop-$id", desktopRevision = 42), cipher))
        }
        db.chatDao().insertMessage(VaultRecords.encode(EncryptedChatMessage("SYNTHETIC-message", "SYNTHETIC-session", "user",
            cipher.encrypt("SYNTHETIC body"), timestamp = 19, status = "REVIEW_REQUIRED", modelName = "SYNTHETIC model",
            attachmentName = "SYNTHETIC.txt", attachmentDataEncrypted = cipher.encrypt("SYNTHETIC bytes"),
            requestId = "stable-request-id", desktopFingerprint = "same-pairing", requireLocal = true, requireHipaa = true,
            attemptedAt = 23, deliveryError = "SYNTHETIC uncertain outcome"), cipher))
        db.documentDao().insertDocument(VaultRecords.encode(EncryptedDocument("SYNTHETIC-document", "SYNTHETIC file", "CAPTURE_PENDING",
            cipher.encrypt("{\"sourceId\":\"stable-capture\",\"localEvidence\":{\"data\":\"c3ludGhldGlj\"}}"),
            "unchanged-checksum", 77, updatedAt = 29, tags = "SYNTHETIC tags"), cipher))
        db.taskDao().insertTask(VaultRecords.encode(DesktopTask("SYNTHETIC-task", "SYNTHETIC title", "REVIEW_REQUIRED",
            modelName = "SYNTHETIC model", resultSummary = "SYNTHETIC result", approvalPrompt = "SYNTHETIC approval"), cipher))
        db.taskDao().insertNotification(VaultRecords.encode(DesktopNotification("SYNTHETIC-notice", "SYNTHETIC notice", "SYNTHETIC message",
            timestamp = 31, isRead = true, isAcknowledged = true, assignedTo = "SYNTHETIC owner", relatedAction = "stable-action"), cipher))
        for ((i, status) in listOf("PENDING", "ACKNOWLEDGED", "LOCAL", "REVIEW_REQUIRED").withIndex()) {
            db.pendingOperationDao().enqueue(PendingOperation("SYNTHETIC-operation-$i", "MEETING_AUDIO",
                cipher.encrypt("{\"sourceId\":\"stable-recording\",\"audio\":\"c3ludGhldGlj\"}"), "same-pairing", 37L + i,
                status = status, attemptedAt = if (i == 0) null else 41L, serverId = "stable-server-$i"))
        }
    }
    // REPLACE can change SQLite physical order. Compare complete records by stable ID.
    private suspend fun logical(db: AppDatabase): List<Any> {
        val values = mutableListOf<Any>()
        for (row in db.chatDao().allSessionsForMigration().sortedBy { it.id }) values.add(VaultRecords.decode(row, cipher).let {
            it.copy(lastMessagePreviewEncrypted = if (it.lastMessagePreviewEncrypted.isEmpty()) "" else cipher.decrypt(it.lastMessagePreviewEncrypted))
        })
        for (row in db.chatDao().allMessagesForMigration().sortedBy { it.id }) values.add(VaultRecords.decode(row, cipher).let {
            it.copy(encryptedContent = cipher.decrypt(it.encryptedContent), attachmentDataEncrypted = it.attachmentDataEncrypted?.let(cipher::decrypt))
        })
        for (row in db.documentDao().allForMigration().sortedBy { it.id }) values.add(VaultRecords.decode(row, cipher).let { it.copy(encryptedContent = cipher.decrypt(it.encryptedContent)) })
        values.addAll(db.taskDao().allTasksForMigration().sortedBy { it.id }.map { VaultRecords.decode(it, cipher) })
        values.addAll(db.taskDao().allNotificationsForMigration().sortedBy { it.id }.map { VaultRecords.decode(it, cipher) })
        values.addAll(db.pendingOperationDao().allAfterForPolicy(null, 100).sortedBy { it.id }.map { it.copy(encryptedPayload = cipher.decrypt(it.encryptedPayload)) })
        return values
    }
    private suspend fun ciphertexts(db: AppDatabase): List<String> = buildList {
        db.chatDao().allSessionsForMigration().sortedBy { it.id }.forEach { add(it.title); add(it.modelUsed); if (it.lastMessagePreviewEncrypted.isNotEmpty()) add(it.lastMessagePreviewEncrypted) }
        db.chatDao().allMessagesForMigration().sortedBy { it.id }.forEach { add(it.encryptedContent); add(it.modelName); it.attachmentName?.let(::add); it.attachmentDataEncrypted?.let(::add); it.deliveryError?.let(::add) }
        db.documentDao().allForMigration().sortedBy { it.id }.forEach { add(it.fileName); add(it.tags); add(it.encryptedContent) }
        db.taskDao().allTasksForMigration().sortedBy { it.id }.forEach { add(it.title); add(it.modelName); it.resultSummary?.let(::add); it.approvalPrompt?.let(::add) }
        db.taskDao().allNotificationsForMigration().sortedBy { it.id }.forEach { add(it.title); add(it.message); it.assignedTo?.let(::add); it.relatedAction?.let(::add) }
        db.pendingOperationDao().allAfterForPolicy(null, 100).sortedBy { it.id }.forEach { add(it.encryptedPayload) }
    }
    @Test fun everyEncryptedLeafBothDatabasesAndAllOutboxStatesPreserveExactLogicalRecords() = runBlocking {
        val real = database(); val demo = database(); seed(real); seed(demo)
        val before = listOf(logical(real), logical(demo))
        for (target in listOf(10, 20, 30, 5)) {
            for (db in listOf(real, demo)) {
                VaultPolicyMigration.convert(db, { cipher.rewrite(it, target) }, {})
                assertTrue(ciphertexts(db).all { PolicyVaultCodec.keyId(it) == target })
            }
            assertEquals(before, listOf(logical(real), logical(demo)))
        }
    }
    @Test fun reexecutionIsIdempotentAndDoesNotDuplicateWorkOrRegenerateCiphertexts() = runBlocking {
        val db = database(); seed(db)
        VaultPolicyMigration.convert(db, { cipher.rewrite(it, 10) }, {})
        val converted = ciphertexts(db); val records = logical(db)
        VaultPolicyMigration.convert(db, { cipher.rewrite(it, 10) }, {})
        assertEquals(converted, ciphertexts(db)); assertEquals(records, logical(db))
    }
    @Test fun interruptionLeavesOnlyAuthenticatedRecoverableRowsAndResumeFinishes() = runBlocking {
        val db = database(); seed(db); val before = logical(db)
        var calls = 0
        try {
            VaultPolicyMigration.convert(db, { if (++calls == 4) error("SYNTHETIC interrupted"); cipher.rewrite(it, 20) }, {})
            fail("An interrupted batch must fail, not adopt the new policy.")
        } catch (_: IllegalStateException) { }
        assertEquals(before, logical(db))
        assertTrue(ciphertexts(db).all { PolicyVaultCodec.keyId(it) in setOf(0, 20) })
        VaultPolicyMigration.convert(db, { cipher.rewrite(it, 20) }, {})
        assertEquals(before, logical(db)); assertTrue(ciphertexts(db).all { PolicyVaultCodec.keyId(it) == 20 })
    }
    @Test fun revokedAccessOrMalformedCiphertextNeverCompletesAMigration() = runBlocking {
        val db = database(); seed(db); val before = ciphertexts(db)
        try { VaultPolicyMigration.convert(db, { cipher.rewrite(it, 30) }, { throw VaultUnavailableException() }); fail("Locked migration must fail.") }
        catch (_: VaultUnavailableException) { }
        assertEquals(before, ciphertexts(db))
        val row = db.chatDao().allSessionsForMigration().single { it.id.isEmpty() }
        db.chatDao().insertSession(row.copy(title = "PAI3:99:invalid"))
        try { VaultPolicyMigration.convert(db, { cipher.rewrite(it, 30) }, {}); fail("Corrupt authenticated data must fail.") }
        catch (_: IllegalArgumentException) { }
        assertEquals("PAI3:99:invalid", db.chatDao().allSessionsForMigration().single { it.id == row.id }.title)
    }
}
