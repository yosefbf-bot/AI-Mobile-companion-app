package com.example.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.data.model.DesktopNodeConfig
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Optional, separate credential that can fetch and acknowledge generic reminder IDs only.
 * It cannot open the private vault or authorize chats, files, captures, actions, or status.
 */
class RestrictedNotificationStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "private-ai-notifications-v1.bin"))
    fun isEnabled(): Boolean = file.baseFile.exists()

    @Synchronized fun save(config: DesktopNodeConfig, enabled: Boolean) {
        if (!enabled) { clear(); return }
        check(EncryptionManager.isUnlocked.value) { "Unlock the private workspace before enabling notifications." }
        require(!config.simulatedNodeEnabled && config.scheme == "https")
        require(config.notificationKey.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Pair with a notification-scoped desktop device first." }
        require(config.pairKey != config.notificationKey) { "The full workspace credential cannot be used for background notifications." }
        val json = JSONObject().put("version", 1).put("kind", "notification-only")
            .put("hostIp", config.hostIp).put("port", config.port)
            .put("certificateDer", config.certificateDer).put("certificateFingerprint", config.certificateFingerprint)
            .put("notificationKey", config.notificationKey)
        val encrypted = VaultCodec.encrypt(json.toString(), key()).toByteArray(Charsets.UTF_8)
        require(encrypted.size <= MAX_CONFIG_BYTES)
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    @Synchronized fun load(): DesktopNodeConfig? {
        if (!file.baseFile.exists()) return null
        require(file.baseFile.length() <= MAX_CONFIG_BYTES)
        val json = JSONObject(VaultCodec.decrypt(file.readFully().toString(Charsets.UTF_8), key(create = false)))
        require(json.getInt("version") == 1 && json.getString("kind") == "notification-only")
        val token = json.getString("notificationKey")
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return DesktopNodeConfig(hostIp = json.getString("hostIp"), port = json.getInt("port"),
            pairKey = token, scheme = "https", simulatedNodeEnabled = false,
            certificateDer = json.getString("certificateDer"),
            certificateFingerprint = json.getString("certificateFingerprint"),
            friendlyName = "Background reminder metadata")
    }

    @Synchronized fun clear() { file.delete() }

    private fun key(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            if (!create) throw VaultUnavailableException()
            // Explicitly limited notification metadata is the only permitted unauthenticated capability.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            }.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as? SecretKey ?: throw VaultUnavailableException()
    }

    companion object {
        private const val KEY_ALIAS = "private_ai_notifications_v1"
        private const val MAX_CONFIG_BYTES = 128 * 1024
    }
}
