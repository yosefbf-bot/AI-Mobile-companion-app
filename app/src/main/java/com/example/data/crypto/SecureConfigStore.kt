package com.example.data.crypto

import android.content.Context
import android.util.AtomicFile
import com.example.data.model.BatteryOptimizationMode
import com.example.data.model.DesktopNodeConfig
import org.json.JSONObject
import java.io.File

/** Pair secrets and certificate trust live only in encrypted, non-backed-up storage. */
class SecureConfigStore(context: Context, private val cipher: VaultCipher = EncryptionManager) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "private-ai-gateway-v2.bin"))

    @Synchronized
    fun load(): DesktopNodeConfig? {
        if (!atomicExists(file)) return null
        val raw = atomicRead(file, 128 * 1024)
        require(raw.size <= 128 * 1024) { "Gateway configuration exceeds its size limit." }
        val data = JSONObject(cipher.decrypt(raw.toString(Charsets.UTF_8)))
        require(data.getInt("version") == 2)
        return DesktopNodeConfig(
            hostIp = data.getString("hostIp"),
            port = data.getInt("port"),
            pairKey = data.getString("pairKey"),
            friendlyName = data.getString("friendlyName"),
            batteryMode = BatteryOptimizationMode.valueOf(data.getString("batteryMode")),
            autoSyncIntervalMinutes = data.getInt("autoSyncIntervalMinutes"),
            simulatedNodeEnabled = false,
            scheme = "https",
            certificateDer = data.getString("certificateDer"),
            certificateFingerprint = data.getString("certificateFingerprint"),
            notificationKey = data.optString("notificationKey", ""),
            backgroundNotificationsEnabled = data.optBoolean("backgroundNotificationsEnabled", false),
            secureClinicalMode = data.optBoolean("secureClinicalMode", true)
        )
    }

    @Synchronized
    fun save(config: DesktopNodeConfig, beforeCommit: () -> Unit = {}) {
        require(config.scheme == "https" && !config.simulatedNodeEnabled) { "Only a real HTTPS pairing is persisted." }
        val data = JSONObject().put("version", 2).put("hostIp", config.hostIp)
            .put("port", config.port).put("pairKey", config.pairKey)
            .put("friendlyName", config.friendlyName).put("batteryMode", config.batteryMode.name)
            .put("autoSyncIntervalMinutes", config.autoSyncIntervalMinutes)
            .put("certificateDer", config.certificateDer)
            .put("certificateFingerprint", config.certificateFingerprint)
            .put("notificationKey", config.notificationKey)
            .put("backgroundNotificationsEnabled", config.backgroundNotificationsEnabled)
            .put("secureClinicalMode", config.secureClinicalMode)
        val bytes = cipher.encrypt(data.toString()).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..128 * 1024)
        atomicWrite(file, bytes, beforeCommit)
        check(atomicRead(file, 128 * 1024).contentEquals(bytes))
    }

    private val syncFile = AtomicFile(File(context.noBackupFilesDir, "private-ai-last-sync-v1.bin"))
    @Synchronized fun lastSync(config: DesktopNodeConfig): Long {
        if(config.simulatedNodeEnabled || !config.isPaired || !atomicExists(syncFile)) return 0
        val data = JSONObject(cipher.decrypt(atomicRead(syncFile, 4096).toString(Charsets.UTF_8)))
        return if(data.getString("destination") == com.example.data.network.PairingConfigParser.destinationIdentity(config)) data.getLong("at").coerceAtLeast(0) else 0
    }
    @Synchronized fun recordSync(config: DesktopNodeConfig, at: Long) {
        require(config.isPaired && !config.simulatedNodeEnabled && at > 0)
        val data = JSONObject().put("destination", com.example.data.network.PairingConfigParser.destinationIdentity(config)).put("at", at)
        val bytes = cipher.encrypt(data.toString()).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..4096)
        atomicWrite(syncFile, bytes, {})
        check(atomicRead(syncFile, 4096).contentEquals(bytes))
    }
    internal fun hasProtectedFiles(): Boolean = atomicExists(file) || atomicExists(syncFile)

    /** Preserve exact decrypted JSON/identity while moving its authenticated key format. */
    @Synchronized internal fun rewrapForPolicy(rewrite: (String) -> String, checkAccess: () -> Unit) {
        for ((protectedFile, limit) in listOf(file to 128 * 1024, syncFile to 4096)) {
            checkAccess()
            if (!atomicExists(protectedFile)) continue
            val before = atomicRead(protectedFile, limit).toString(Charsets.UTF_8)
            val after = rewrite(before)
            checkAccess()
            val replacement = after.toByteArray(Charsets.UTF_8)
            require(replacement.size in 1..limit) { "Converted protected file exceeds its supported size." }
            if (after != before) atomicWrite(protectedFile, replacement, checkAccess)
            checkAccess()
            check(atomicRead(protectedFile, limit).toString(Charsets.UTF_8) == after)
        }
    }
    @Synchronized fun clear() { file.delete(); syncFile.delete() }
}
