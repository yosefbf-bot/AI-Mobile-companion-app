package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [com.example.testutil.AndroidAtomicFileShadow::class])
class VaultPolicyPreparationTest {
    private fun context(directory: File) = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir() = directory
    }
    @Test fun unsupportedNewTargetRollsBackBeforeAnyRecordCanBeRewrittenAndRestartKeepsOldPolicy() {
        val dir = Files.createTempDirectory("synthetic-policy-rejection-").toFile()
        try {
            val cipher = TestVaultCipher(); val store = VaultPolicyStore(context(dir), cipher)
            val request = VaultPolicyState(0, 30, "KEY_REQUESTED"); store.write(request)
            val result = VaultPolicyPreparation.finish(store, request) { _, create -> assertTrue(create); error("SYNTHETIC unsupported target") }
            assertTrue(result.rejected); assertEquals(VaultPolicyState(), result.state)
            assertEquals(VaultPolicyState(), VaultPolicyStore(context(dir), cipher).read())
        } finally { dir.deleteRecursively() }
    }
    @Test fun preparedOrPartiallyConvertedTargetNeverRollsBackOnMissingOrInvalidKey() {
        val dir = Files.createTempDirectory("synthetic-policy-prepared-").toFile()
        try {
            val store = VaultPolicyStore(context(dir), TestVaultCipher())
            for (phase in listOf("PREPARED", "REAL_DB_DONE", "DEMO_DB_DONE", "FILES_DONE")) {
                val pending = VaultPolicyState(0, 10, phase); store.write(pending)
                assertThrows(IllegalStateException::class.java) {
                    VaultPolicyPreparation.finish(store, pending) { _, create -> assertFalse(create); error("SYNTHETIC missing key") }
                }
                assertEquals(pending, store.read())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedRollbackCommitLeavesRecoverableRequestAndDoesNotClaimSuccess() {
        val dir = Files.createTempDirectory("synthetic-policy-commit-failure-").toFile()
        try {
            val cipher = TestVaultCipher(); val store = VaultPolicyStore(context(dir), cipher)
            val request = VaultPolicyState(0, 20, "KEY_REQUESTED"); store.write(request)
            assertThrows(VaultUnavailableException::class.java) {
                VaultPolicyPreparation.finish(store, request) { _, _ -> cipher.available = false; error("SYNTHETIC target failure") }
            }
            cipher.available = true
            assertEquals(request, store.read())
        } finally { dir.deleteRecursively() }
    }
}
