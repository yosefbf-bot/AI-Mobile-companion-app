package com.example.data.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal data class VaultPolicyState(val current: Int = 0, val target: Int? = null, val phase: String = "COMMITTED") {
    init {
        PolicyVaultCodec.minutes(current)
        target?.let { PolicyVaultCodec.minutes(it) }
        require(phase in PHASES && (target == null) == (phase == "COMMITTED"))
    }
    companion object { val PHASES = setOf("KEY_REQUESTED", "PREPARED", "REAL_DB_DONE", "DEMO_DB_DONE", "FILES_DONE", "COMMITTED") }
}

/** The journal contains policy only. Its original OS-authenticated key never changes. */
internal class VaultPolicyStore(context: Context, private val cipher: VaultCipher) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "private-ai-vault-policy-v3.bin"))
    fun exists(): Boolean = atomicExists(file)

    @Synchronized fun read(): VaultPolicyState {
        if (!exists()) return VaultPolicyState()
        val bytes = atomicRead(file, 16 * 1024)
        val json = JSONObject(cipher.decrypt(bytes.toString(Charsets.UTF_8)))
        require(json.getInt("version") == 1)
        return VaultPolicyState(json.getInt("current"), if (json.isNull("target")) null else json.getInt("target"), json.getString("phase"))
    }

    @Synchronized fun write(state: VaultPolicyState, beforeCommit: () -> Unit = {}) {
        val json = JSONObject().put("version", 1).put("current", state.current)
            .put("target", state.target ?: JSONObject.NULL).put("phase", state.phase)
        val encrypted = cipher.encrypt(json.toString()).toByteArray(Charsets.UTF_8)
        atomicWrite(file, encrypted, beforeCommit)
        check(read() == state) { "The protected policy commit could not be verified." }
    }
}

/** Include recovery candidates; a backup-only store must never be treated as absent. */
internal fun atomicExists(file: AtomicFile): Boolean = listOf(file.baseFile,
    File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any { it.exists() }

internal fun atomicRead(file: AtomicFile, limit: Int): ByteArray {
    // AtomicFile's recovery backup is authoritative even if an interrupted base is
    // empty or oversized. Never validate that uncommitted base instead of the backup.
    val backup = File(file.baseFile.path + ".bak")
    val authoritative = if (backup.exists()) backup else file.baseFile
    require(authoritative.exists() && authoritative.length() in 1..limit.toLong()) { "Invalid protected file size." }
    return file.readFully().also { require(it.size in 1..limit) { "Invalid protected file size." } }
}

internal fun atomicWrite(file: AtomicFile, bytes: ByteArray, beforeCommit: () -> Unit) {
    beforeCommit()
    val stream = file.startWrite()
    try { stream.write(bytes); stream.flush(); stream.fd.sync(); beforeCommit(); file.finishWrite(stream) }
    catch (failure: Exception) { file.failWrite(stream); throw failure }
}
