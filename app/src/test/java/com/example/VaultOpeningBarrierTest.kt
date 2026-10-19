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
            assertEquals("SYNTHETIC retained body", actual.decrypt(repository.documents.first().single().encryptedContent))
        } finally { release.countDown(); repository.close(); db.close() }
    }
}
