package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.model.DesktopNodeConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultPolicyStorageTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val cipher = PolicyTestCipher()
    @Before fun setup() {
        directory = Files.createTempDirectory("synthetic-vault-policy-").toFile()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
        }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun absentJournalKeepsOriginalPolicyAndEncryptedPendingStateRoundTrips() {
        val store = VaultPolicyStore(context, cipher)
        assertEquals(VaultPolicyState(), store.read())
        val pending = VaultPolicyState(0, 20, "PREPARED")
        store.write(pending)
        assertEquals(pending, store.read())
        assertFalse(File(directory, "private-ai-vault-policy-v3.bin").readText().contains("PREPARED"))
        store.write(VaultPolicyState(20))
        assertEquals(VaultPolicyState(20), store.read())
    }
    @Test fun authoritativeBackupIsRecoveredEvenWhenInterruptedBaseIsInvalid() {
        val store = VaultPolicyStore(context, cipher)
        val pending = VaultPolicyState(0, 10, "PREPARED")
        store.write(pending)
        val file = File(directory, "private-ai-vault-policy-v3.bin")
        file.copyTo(File(file.path + ".bak")); file.writeText("")
        assertEquals(pending, store.read())
        file.copyTo(File(file.path + ".bak")); assertTrue(file.delete())
        assertEquals(pending, store.read())
    }
    @Test fun corruptionOrOnlyUncommittedNewFileCannotBecomeDefaultSuccess() {
        val file = File(directory, "private-ai-vault-policy-v3.bin")
        file.writeText("corrupt")
        assertThrows(Exception::class.java) { VaultPolicyStore(context, cipher).read() }
        assertEquals("corrupt", file.readText())
        assertTrue(file.delete()); File(file.path + ".new").writeText("uncommitted")
        assertThrows(Exception::class.java) { VaultPolicyStore(context, cipher).read() }
    }
    @Test fun interruptedAtomicCommitKeepsPreviousJournalAndTarget() {
        val store = VaultPolicyStore(context, cipher)
        val pending = VaultPolicyState(0, 30, "PREPARED")
        store.write(pending)
        var calls = 0
        assertThrows(Exception::class.java) { store.write(pending.copy(phase = "FILES_DONE")) {
            if (++calls == 2) error("SYNTHETIC interrupted before commit")
        } }
        assertEquals(pending, store.read())
    }
    @Test fun gatewayAndLastSyncBackupOnlyFilesSurvivePolicyConversionWithExactBinding() {
        val config = DesktopNodeConfig(hostIp = "127.0.0.1", port = 8443, pairKey = "A".repeat(43),
            certificateDer = "U1lOVEhFVElD", certificateFingerprint = "1".repeat(64), scheme = "https",
            friendlyName = "SYNTHETIC PC")
        val store = SecureConfigStore(context, cipher)
        store.save(config); store.recordSync(config, 1234L)
        for (name in listOf("private-ai-gateway-v2.bin", "private-ai-last-sync-v1.bin")) {
            val file = File(directory, name); file.copyTo(File(file.path + ".bak")); assertTrue(file.delete())
        }
        store.rewrapForPolicy({ cipher.rewrite(it, 10) }, {})
        cipher.active = 10
        assertEquals(config, store.load())
        assertEquals(1234L, store.lastSync(config))
        for (name in listOf("private-ai-gateway-v2.bin", "private-ai-last-sync-v1.bin"))
            assertEquals(10, PolicyVaultCodec.keyId(File(directory, name).readText()))
    }
}
