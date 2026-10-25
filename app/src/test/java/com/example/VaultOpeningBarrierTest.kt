package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.VaultCipher
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedDocument
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultOpeningBarrierTest {
    @Test fun eachOpeningMigratesRealDatabaseOnceAndNeverHydratesCurrentBodies() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, java.util.concurrent.Executor { it.run() }).build()
        val cipher = TestVaultCipher()
        val repository = PrivateAIRepository(db, cipher = cipher)
        try {
            db.documentDao().insertDocument(com.example.data.crypto.VaultRecords.encode(EncryptedDocument(
                "synthetic-current", "Synthetic.txt", "TEXT", cipher.encrypt("SYNTHETIC " + "x".repeat(3 * 1024 * 1024)), "same", 3L * 1024 * 1024), cipher))
            repeat(2) {
                queries.clear()
                repository.onUnlocked().join()
                assertTrue(repository.vaultReady.value)
                // synchronizedList protects individual operations, not iteration
                // while Room's background invalidation/polling queries append.
                val observed = synchronized(queries) { queries.toList() }
                assertEquals(1, observed.count { it.contains("SELECT id FROM documents WHERE storageVersion = 0", true) })
                assertFalse(observed.any { it.contains("substr(encryptedContent", true) || it.contains("length(encryptedContent)", true) })
                repository.onLocked()
            }
        } finally { repository.close(); db.close() }
    }

    @Test fun corruptLegacyDocumentRollsBackTheEarlierBodyAndMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val cipher = TestVaultCipher()
        try {
            val original = EncryptedDocument("a", "Synthetic retained", "TEXT", "ENC:SYNTHETIC retained", "same", 20)
            db.documentDao().insertDocument(original)
            db.documentDao().insertDocument(original.copy(id = "b", encryptedContent = "corrupt"))
            try { db.migrateLegacyContent(cipher); fail("Corrupt legacy content must not partially migrate.") } catch (_: Exception) { }
            assertEquals(original, db.documentDao().hydratedById("a"))
            assertEquals("corrupt", db.documentDao().hydratedById("b")!!.encryptedContent)
        } finally { db.close() }
    }

    @Test fun lockedOpeningAndNewOpeningJoinBeforeOldStorageWorkCanBeAdopted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
        val actual = TestVaultCipher()
        val blocking = object : VaultCipher {
            override fun encrypt(plaintext: String): String {
                if (first.compareAndSet(true, false)) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "SYNTHETIC test gate timed out" }
                }
                return actual.encrypt(plaintext)
            }
            override fun decrypt(encryptedPayload: String) = actual.decrypt(encryptedPayload)
        }
        val repository = PrivateAIRepository(db, cipher = blocking)
        try {
            db.documentDao().insertDocument(EncryptedDocument("SYNTHETIC-legacy", "SYNTHETIC legacy", "TEXT",
                "ENC:SYNTHETIC retained body", "unchanged", 10, tags = "SYNTHETIC", storageVersion = 0))
            val old = repository.onUnlocked()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            repository.onLocked()
            val newer = repository.onUnlocked()
            assertFalse(repository.vaultReady.value)
            release.countDown()
            withTimeout(20_000) { old.join(); newer.join() }
            assertTrue(repository.vaultReady.value)
            assertEquals(1, repository.documents.first().size)
            assertEquals("", repository.documents.first().single().encryptedContent)
            assertEquals("SYNTHETIC retained body", repository.openDocument("SYNTHETIC-legacy")!!.second)
        } finally { release.countDown(); repository.close(); db.close() }
    }
}
