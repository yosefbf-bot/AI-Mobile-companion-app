package com.example

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedDocument
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureLockAndLargeRecordTest {
    @Test fun absentUnknownAndInsecurePhoneCannotQualifyForLongerSessions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(DeviceScreenLockState.UNAVAILABLE, DeviceScreenLock.read(null))
        val unknown = object : ContextWrapper(context) { override fun getSystemService(name: String): Any? = null }
        assertEquals(DeviceScreenLockState.UNAVAILABLE, DeviceScreenLock.read(unknown))
        val guard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(guard).setIsDeviceSecure(false)
        assertEquals(DeviceScreenLockState.NOT_CONFIGURED, DeviceScreenLock.read(context))
        shadowOf(guard).setIsDeviceSecure(true); shadowOf(guard).setIsDeviceLocked(true)
        assertEquals(DeviceScreenLockState.SECURE_LOCKED, DeviceScreenLock.read(context))
        shadowOf(guard).setIsDeviceLocked(false)
        assertEquals(DeviceScreenLockState.SECURE_UNLOCKED, DeviceScreenLock.read(context))
    }
    @Test fun largeDocumentBodiesSurviveFlowsAndPolicyConversionWithoutGiantCursorRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val cipher = PolicyTestCipher()
        try {
            val plain = "SYNTHETIC evidence " + "x".repeat(3 * 1024 * 1024)
            val row = VaultRecords.encode(EncryptedDocument("SYNTHETIC-large", "SYNTHETIC.txt", "CAPTURE_PENDING", cipher.encrypt(plain), "same", plain.length.toLong()), cipher)
            db.documentDao().insertDocument(row)
            assertEquals(row, db.documentDao().getDocumentById(row.id).first())
            assertEquals(row, db.documentDao().getAllDocuments().first().single())
            VaultPolicyMigration.convert(db, { cipher.rewrite(it, 10) }, {})
            cipher.active = 10
            val migrated = db.documentDao().allForMigration().single()
            assertEquals(plain, cipher.decrypt(migrated.encryptedContent))
            assertEquals(row.checksumSha256, migrated.checksumSha256)
        } finally { db.close() }
    }
    @Test fun stopDoesNotCancelLocalSaveAndNewOpeningWaitsForItsMethodCompletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val actual = PolicyTestCipher(); val armed = AtomicBoolean(false)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val cipher = object : VaultCipher {
            override fun encrypt(plaintext: String): String {
                if (armed.compareAndSet(true, false)) { started.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                return actual.encrypt(plaintext)
            }
            override fun decrypt(encryptedPayload: String) = actual.decrypt(encryptedPayload)
        }
        val repository = PrivateAIRepository(db, cipher = cipher)
        try {
            repository.onUnlocked().join(); assertTrue(repository.vaultReady.value)
            armed.set(true)
            val save = async(Dispatchers.IO) { repository.saveDocument("SYNTHETIC", "TEXT", "SYNTHETIC retained", "") }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            repository.stopStreaming(); assertTrue(save.isActive)
            val opening = repository.onUnlocked()
            assertFalse(repository.vaultReady.value)
            release.countDown(); withTimeout(20_000) { save.await(); opening.join() }
            assertTrue(repository.vaultReady.value)
            assertEquals(1, repository.documents.first().size)
        } finally { release.countDown(); repository.close(); db.close() }
    }
}
