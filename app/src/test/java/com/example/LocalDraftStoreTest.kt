package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDraftStoreTest {
    private fun database(callback: androidx.room.RoomDatabase.QueryCallback? = null): AppDatabase {
        val builder = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        if (callback != null) builder.setQueryCallback(callback, java.util.concurrent.Executor { it.run() })
        return builder.build()
    }
    private fun store(db: AppDatabase, cipher: PolicyTestCipher, scope: String = "SYNTHETIC") =
        LocalDraftStore(cipher, { route -> DraftScope(db, "$scope:$route", 1) }, { check(it.workspace == 1L) }, 60_000)
    private val clearMatching: (String, String) -> String = { current, submitted ->
        val now = JSONObject(current); val before = JSONObject(submitted)
        if (now.optString("text") == before.optString("text")) now.put("text", "")
        if (now.opt("evidence")?.toString() == before.opt("evidence")?.toString()) now.put("evidence", JSONObject.NULL)
        now.toString()
    }
    @Test fun localAutoSaveSurvivesReopenWithoutLibraryOrOutboxExposure() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val first = store(db, cipher)
        try {
            var token = first.load("capture").token
            token = first.updateFields(token, "{\"text\":\"SYNTHETIC retained note\"}", null, true)
            first.flush(); assertTrue(first.status(token).persisted)
            val restored = store(db, cipher)
            try {
                assertEquals("SYNTHETIC retained note", JSONObject(restored.load("capture").form).getString("text"))
                assertEquals(0, db.documentDao().allForMigration().size)
                assertEquals(0, db.pendingOperationDao().pending("").size)
            } finally { restored.close() }
        } finally { first.flush(); first.close(); db.close() }
    }
    @Test fun offRetainsDiskAndAcknowledgmentNeverPersistsNewerOffEdits() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val first = store(db, cipher)
        try {
            var token = first.load("chat:1").token
            token = first.updateFields(token, "{\"text\":\"SYNTHETIC submitted\"}", null, true)
            first.flush(); val submitted = first.current(token).form
            token = first.updateFields(token, "{\"text\":\"SYNTHETIC newer OFF edit\"}", null, false)
            first.flush(); assertFalse(first.status(token).persisted); assertTrue(first.status(token).hasSavedDraft)
            token = first.acknowledge(token, submitted, clearMatching); first.flush()
            assertEquals("SYNTHETIC newer OFF edit", JSONObject(first.current(token).form).getString("text"))
            val reopened = store(db, cipher)
            try { assertEquals("", JSONObject(reopened.load("chat:1").form).optString("text")) }
            finally { reopened.close() }
        } finally { first.close(); db.close() }
    }
    @Test fun confirmedDeleteRejectsQueuedOldTokensAndSuccessorCanBeUsed() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            val original = drafts.load("capture").token
            val saved = drafts.updateFields(original, "{\"text\":\"SYNTHETIC delete\"}", null, true)
            val deleted = drafts.delete(saved)
            assertEquals("{}", deleted.form); assertTrue(deleted.persisted); assertFalse(deleted.hasSavedDraft)
            assertThrows(IllegalStateException::class.java) { drafts.updateFields(saved, "{\"text\":\"stale\"}", null, true) }
            val next = drafts.updateFields(deleted.token, "{\"text\":\"SYNTHETIC replacement\"}", null, true)
            drafts.flush(); assertTrue(drafts.status(next).persisted)
            assertEquals(1L, db.localDraftDao().count())
        } finally { drafts.close(); db.close() }
    }
    @Test fun firstWriteInFlightIsAcknowledgedBeforeItCanRestoreSubmittedDraft() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
        val db = database(object : androidx.room.RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.contains("INSERT OR REPLACE INTO `local_drafts`") && first.compareAndSet(true, false)) {
                    started.countDown(); check(release.await(15, TimeUnit.SECONDS))
                }
            }
        })
        val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            var token = drafts.updateFields(drafts.load("chat:1").token, "{\"text\":\"SYNTHETIC submitted\"}", null, true)
            val submitted = drafts.current(token).form
            val flushing = async(Dispatchers.IO) { drafts.flush() }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            token = drafts.acknowledge(token, submitted, clearMatching)
            release.countDown(); withTimeout(20_000) { flushing.await(); drafts.flush() }
            assertTrue(drafts.status(token).persisted)
            val reopened = store(db, cipher)
            try { assertEquals("", JSONObject(reopened.load("chat:1").form).optString("text")) }
            finally { reopened.close() }
        } finally { release.countDown(); drafts.close(); db.close() }
    }
    @Test fun competingWriterCannotClaimAnotherRowsRevisionAsItsOwnSave() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val first = store(db, cipher); val second = store(db, cipher)
        try {
            val one = first.updateFields(first.load("capture").token, "{\"text\":\"SYNTHETIC one\"}", null, true)
            val two = second.updateFields(second.load("capture").token, "{\"text\":\"SYNTHETIC two\"}", null, true)
            first.flush(); assertTrue(first.status(one).persisted)
            var failed = false
            try { second.flush() } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed); assertFalse(second.status(two).persisted)
            assertEquals("SYNTHETIC two", JSONObject(second.current(two).form).getString("text"))
        } finally { first.close(); second.close(); db.close() }
    }
    @Test fun savedAndOffRamDraftsSurvivePolicyChangesAndReturnToOriginalFiveMinuteFormat() = runBlocking {
        val real = database(); val demo = database(); val cipher = PolicyTestCipher(); val saved = store(real, cipher); val off = store(demo, cipher, "DEMO")
        try {
            val savedToken = saved.updateFields(saved.load("capture").token, "{\"text\":\"SYNTHETIC saved\"}", null, true)
            val offToken = off.updateFields(off.load("capture").token, "{\"text\":\"SYNTHETIC OFF\"}", null, false)
            saved.flush(); off.flush()
            for (target in listOf(10, 20, 30, 0)) {
                com.example.data.crypto.VaultPolicyMigration.convert(real, { cipher.rewrite(it, target) }, {})
                com.example.data.crypto.VaultPolicyMigration.convert(demo, { cipher.rewrite(it, target) }, {})
                saved.rewrapMemory({ cipher.rewrite(it, target) }, {}); off.rewrapMemory({ cipher.rewrite(it, target) }, {}); cipher.active = target
                assertTrue(saved.status(savedToken).persisted); assertFalse(off.status(offToken).persisted)
                assertEquals("SYNTHETIC OFF", JSONObject(off.current(offToken).form).getString("text"))
            }
            assertTrue(real.localDraftDao().byId(savedToken.id)!!.encryptedFields!!.startsWith("PAI2:"))
            assertEquals(0L, demo.localDraftDao().count())
        } finally { saved.close(); off.close(); real.close(); demo.close() }
    }
    @Test fun largeEvidenceIsImmutableAcrossTextEditsAndLoadsThroughBoundedSlices() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val drafts = store(db, cipher)
        try {
            val attachment = com.example.data.model.ChatAttachment("SYNTHETIC.txt", "DOCUMENT", "A".repeat(3 * 1024 * 1024), 2L * 1024 * 1024)
            var token = drafts.updateFields(drafts.load("capture").token, "{\"text\":\"first\"}", attachment, true)
            drafts.flush(); val id = db.localDraftDao().byId(token.id)!!.evidenceId!!
            val old = db.withTransaction { db.localDraftDao().readEvidence(id)!! }
            token = drafts.updateFields(token, "{\"text\":\"second\"}", attachment, true)
            drafts.flush(); assertEquals(id, db.localDraftDao().byId(token.id)!!.evidenceId)
            assertEquals(old, db.withTransaction { db.localDraftDao().readEvidence(id) })
            for (target in listOf(10, 0)) {
                com.example.data.crypto.VaultPolicyMigration.convert(db, { cipher.rewrite(it, target) }, {})
                drafts.rewrapMemory({ cipher.rewrite(it, target) }, {}); cipher.active = target
                val converted = db.withTransaction { db.localDraftDao().readEvidence(id)!! }
                token = drafts.updateFields(token, "{\"text\":\"after policy $target\"}", attachment, true)
                drafts.flush(); assertTrue(drafts.status(token).persisted)
                assertEquals(id, db.localDraftDao().byId(token.id)!!.evidenceId)
                assertEquals(converted, db.withTransaction { db.localDraftDao().readEvidence(id) })
            }
            val reopened = store(db, cipher)
            try { assertEquals(attachment.base64Data.length, JSONObject(reopened.load("capture").form).getJSONObject("evidence").getString("data").length) }
            finally { reopened.close() }
        } finally { drafts.close(); db.close() }
    }
    @Test fun pristineFormsDoNotCreateEmptyRowsAndDifferentScopesDoNotShareDrafts() = runBlocking {
        val db = database(); val cipher = PolicyTestCipher(); val real = store(db, cipher, "REAL"); val other = store(db, cipher, "OTHER")
        try {
            var token = real.load("capture").token
            token = real.updateFields(token, "{\"text\":\"\"}", null, true); real.flush()
            assertTrue(real.status(token).persisted); assertEquals(0L, db.localDraftDao().count())
            token = real.updateFields(token, "{\"text\":\"SYNTHETIC private\"}", null, true); real.flush()
            assertEquals("{}", other.load("capture").form)
            assertEquals("SYNTHETIC private", JSONObject(real.current(token).form).getString("text"))
        } finally { real.close(); other.close(); db.close() }
    }
}
