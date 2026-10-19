package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.VaultCipher
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedDocument
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DurableDraftAcknowledgmentTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private fun store(db: AppDatabase, cipher: VaultCipher, allowed: () -> Boolean = { true }) =
        LocalDraftStore(cipher, { check(allowed()); DraftScope(db, "SYNTHETIC:$it", 1) }, { check(allowed()) }, 60_000)
    private fun document(cipher: VaultCipher) = EncryptedDocument("SYNTHETIC-record", "SYNTHETIC", "TEXT", cipher.encrypt("SYNTHETIC saved"), "same", 15)

    @Test fun preparedReceiptCommitsWithRecordAfterLockWithoutAnyPostLockCrypto() = runBlocking {
        val db = database(); val actual = PolicyTestCipher(); var unlocked = true
        val guarded = object : VaultCipher {
            override fun encrypt(plaintext: String): String { check(unlocked); return actual.encrypt(plaintext) }
            override fun decrypt(encryptedPayload: String): String { check(unlocked); return actual.decrypt(encryptedPayload) }
        }
        val drafts = store(db, guarded, { unlocked })
        try {
            val token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"SYNTHETIC submitted\"}", null, true)
            drafts.flush(); val submission = drafts.prepareSubmission(token, drafts.current(token).form, setOf("text", "evidence"))
            val saved = document(guarded)
            unlocked = false; drafts.onLocked()
            DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(saved) }
            assertEquals(1L, db.localDraftDao().acknowledgmentCount())
            assertEquals(1, db.documentDao().allForMigration().size)
            assertThrows(IllegalStateException::class.java) { drafts.current(token) }
            unlocked = true
            drafts.reconcileAcknowledgments(listOf(db))
            assertEquals("{}", drafts.load("capture").form)
            assertEquals(0L, db.localDraftDao().acknowledgmentCount())
        } finally { unlocked = true; drafts.close(); db.close() }
    }
    @Test fun receiptClearsOnlyMatchingDiskAndOffRamFieldsAndReplayIsIdempotent() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            var token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"submitted\",\"location\":\"SYNTHETIC unit\"}", null, true)
            drafts.flush(); val submission = drafts.prepareSubmission(token, drafts.current(token).form, setOf("text", "location"))
            token = drafts.updateFields(token, "{\"text\":\"newer OFF input\",\"location\":\"SYNTHETIC unit\"}", null, false)
            val saved = document(cipher)
            DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(saved) }
            drafts.reconcileAcknowledgments(listOf(db))
            val current = drafts.load("capture")
            assertEquals("newer OFF input", JSONObject(current.form).getString("text"))
            assertEquals("", JSONObject(current.form).optString("location"))
            val before = db.localDraftDao().byId(current.token.id)
            drafts.reconcileAcknowledgments(listOf(db)); assertEquals(before, db.localDraftDao().byId(current.token.id))
            assertTrue(before!!.deleted); assertEquals(0L, db.localDraftDao().acknowledgmentCount())
        } finally { drafts.close(); db.close() }
    }
    @Test fun deletionGenerationProtectsNewDraftEvenWhenItsTextMatchesAnOldReceipt() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            var token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"SYNTHETIC same text\"}", null, true)
            drafts.flush(); val submission = drafts.prepareSubmission(token, drafts.current(token).form, setOf("text"))
            token = drafts.delete(token).token
            token = drafts.updateFields(token, "{\"text\":\"SYNTHETIC same text\"}", null, true); drafts.flush()
            DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(document(cipher)) }
            drafts.reconcileAcknowledgments(listOf(db))
            assertEquals("SYNTHETIC same text", JSONObject(drafts.load("capture").form).getString("text"))
            assertEquals(1L, db.localDraftDao().byId(token.id)!!.deleteGeneration)
        } finally { drafts.close(); db.close() }
    }
    @Test fun receiptCapacityFailureRollsBackSaveRatherThanClaimingUnacknowledgedSuccess() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            val token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"SYNTHETIC retained\"}", null, true)
            drafts.flush(); val submission = drafts.prepareSubmission(token, drafts.current(token).form, setOf("text"))
            repeat(1000) { db.localDraftDao().put(submission.receipt.copy(id = "ack:SYNTHETIC-$it")) }
            val saved = document(cipher); var rejected = false
            try { DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(saved) } }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected); assertTrue(db.documentDao().allForMigration().isEmpty())
            assertEquals("SYNTHETIC retained", JSONObject(drafts.current(token).form).getString("text"))
        } finally { drafts.close(); db.close() }
    }

    @Test fun liveAcknowledgmentThenIdenticalNewWorkSurvivesOldReceiptReplayIncludingOff() = runBlocking {
        for (autoSave in listOf(true, false)) {
            val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
            try {
                var token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"SYNTHETIC same A\"}", null, true)
                drafts.flush(); val submitted = drafts.current(token).form
                val submission = drafts.prepareSubmission(token, submitted, setOf("text", "evidence"))
                val saved = document(cipher)
                DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(saved) }
                val reconcile: (String, String) -> String = { current, before ->
                    val value = JSONObject(current)
                    if (value.optString("text") == JSONObject(before).optString("text")) value.put("text", "")
                    value.toString()
                }
                token = drafts.acknowledge(token, submitted, reconcile, submission)
                drafts.flush()
                token = drafts.updateFields(token, "{\"text\":\"SYNTHETIC same A\"}", null, autoSave)
                drafts.flush(); drafts.reconcileAcknowledgments(listOf(db))
                assertEquals("SYNTHETIC same A", JSONObject(drafts.load("capture").form).getString("text"))
                // Even a duplicate late UI callback for the old submission is ignored.
                val fresh = drafts.load("capture").token
                assertEquals(fresh, drafts.acknowledge(fresh, submitted, reconcile, submission))
                if (autoSave) assertTrue(drafts.status(fresh).persisted)
            } finally { drafts.close(); db.close() }
        }
    }

    @Test fun restartBeforeLatestAutosaveFlushClearsOnlyExactKnownOlderDiskCandidate() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val old = store(db, cipher)
        var restarted: LocalDraftStore? = null
        try {
            var token = old.updateFields(old.load("capture").token, "{\"text\":\"SYNTHETIC older partial\"}", null, true)
            old.flush()
            token = old.updateFields(token, "{\"text\":\"SYNTHETIC complete submitted text\"}", null, true)
            val submission = old.prepareSubmission(token, old.current(token).form, setOf("text", "evidence"))
            val saved = document(cipher)
            DraftAcknowledgments.commit(db, submission) { db.documentDao().insertDocument(saved) }
            // Terminate only this unit fixture's private delayed writer, modeling
            // process death before its admitted latest ciphertext reached SQLite.
            val field = LocalDraftStore::class.java.getDeclaredField("writer").apply { isAccessible = true }
            (field.get(old) as kotlinx.coroutines.CoroutineScope).coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            restarted = store(db, cipher)
            restarted.reconcileAcknowledgments(listOf(db))
            assertEquals("{}", restarted.load("capture").form)
            assertEquals(1, db.documentDao().allForMigration().size)
            assertEquals(0L, db.localDraftDao().acknowledgmentCount())
        } finally { restarted?.close(); old.close(); db.close() }
    }
}
