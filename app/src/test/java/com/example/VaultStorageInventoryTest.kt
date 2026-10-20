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
class VaultStorageInventoryTest {
    private val databases = mutableListOf<AppDatabase>()
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .build().also { databases.add(it) }
    @After fun close() { databases.forEach { it.close() } }
    @Test fun emptyAndGenuinelyRetiredDataCanReceiveTheirFirstProtectedKey() = runBlocking {
        val real = db(); val demo = db()
        assertFalse(VaultStorageInventory.hasProtectedRows(real)); assertFalse(VaultStorageInventory.hasProtectedRows(demo))
        real.documentDao().insertDocument(EncryptedDocument("SYNTHETIC", "SYNTHETIC legacy", "TEXT", "ENC:SYNTHETIC body", "same", 7, storageVersion = 0))
        assertFalse(VaultStorageInventory.hasProtectedRows(real))
    }
    @Test fun protectedVersionAndProtectedBodyInsideLegacyEnvelopeBothRejectReplacement() = runBlocking {
        for (row in listOf(
            EncryptedDocument("SYNTHETIC", "bad", "TEXT", "corrupt", "same", 7, storageVersion = 1),
            EncryptedDocument("SYNTHETIC", "legacy", "TEXT", "PAI2:protected", "same", 7, storageVersion = 0),
            EncryptedDocument("SYNTHETIC", "legacy", "TEXT", "PAI3:20:protected", "same", 7, storageVersion = 0))) {
            val database = db(); database.documentDao().insertDocument(row)
            assertTrue(VaultStorageInventory.hasProtectedRows(database))
        }
    }
    @Test fun acknowledgedAndLocalOutboxRowsAlsoProveAnExistingKeyIsRequired() = runBlocking {
        for (status in listOf("PENDING", "LOCAL", "ACKNOWLEDGED", "REVIEW_REQUIRED")) {
            val database = db()
            database.pendingOperationDao().enqueue(PendingOperation("SYNTHETIC", "MEETING_STATE", "protected", "binding", 1, status))
            assertTrue(VaultStorageInventory.hasProtectedRows(database))
        }
    }
}
