package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LocalDraft
import com.example.data.model.DesktopNodeConfig
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageBoundaryTest {
    @Test fun expandedConfigurationIsRejectedBeforeCommittedFilesCanBeOverwritten() {
        val directory = Files.createTempDirectory("SYNTHETIC-policy-boundary").toFile()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) { override fun getNoBackupFilesDir() = directory }
        val cipher = PolicyTestCipher(); val store = SecureConfigStore(context, cipher)
        try {
            val config = DesktopNodeConfig(hostIp = "127.0.0.1", pairKey = "A".repeat(43), certificateDer = "U1lOVEhFVElD", certificateFingerprint = "1".repeat(64), scheme = "https")
            for (limit in listOf(128 * 1024, 4096)) {
                store.save(config); store.recordSync(config, 1234)
                val names = listOf("private-ai-gateway-v2.bin", "private-ai-last-sync-v1.bin")
                val before = names.associateWith { File(directory, it).readBytes() }
                assertThrows(IllegalArgumentException::class.java) { store.rewrapForPolicy({ value ->
                    val sync = JSONObject(cipher.decrypt(value)).has("destination")
                    if ((limit == 4096) == sync) "x".repeat(limit + 1) else value
                }, {}) }
                names.forEach { assertArrayEquals(before.getValue(it), File(directory, it).readBytes()) }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun loadingManySavedDraftsCannotBypassEncryptedRamQuota() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val cipher = PolicyTestCipher()
        val drafts = LocalDraftStore(cipher, { DraftScope(db, "SYNTHETIC:$it", 1) }, {}, 60_000)
        try {
            repeat(105) { index ->
                val id = "SYNTHETIC:$index"
                val fields = JSONObject().put("version", 1).put("id", id).put("fields", JSONObject().put("text", "x".repeat(255 * 1024)))
                    .put("evidenceId", JSONObject.NULL).put("evidenceDigest", JSONObject.NULL)
                db.localDraftDao().put(LocalDraft(id, 1, false, cipher.encrypt(fields.toString()), null, 1))
            }
            var rejected = false; var last: LocalDraftToken? = null
            for (index in 0 until 105) try { last = drafts.load(index.toString()).token } catch (_: IllegalStateException) { rejected = true; break }
            assertTrue(rejected); assertNotNull(last)
            assertEquals(255 * 1024, JSONObject(drafts.current(last!!).form).getString("text").length)
            assertEquals(105L, db.localDraftDao().count())
        } finally { drafts.close(); db.close() }
    }
}
