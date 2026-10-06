package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface VaultCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encryptedPayload: String): String
}

class VaultUnavailableException(cause: Throwable? = null) :
    IllegalStateException("Vault unavailable. Unlock with the device screen lock or biometrics.", cause)

/** Keys never leave Android Keystore. A UI lock also revokes application access. */
object EncryptionManager : VaultCipher {
    private const val KEY_ALIAS = "private_ai_vault_v2"
    private const val AUTH_SECONDS = 300
    @Volatile private var applicationContext: Context? = null
    private val access = MutableStateFlow(false)
    private val generation = AtomicLong()
    val isUnlocked: StateFlow<Boolean> = access.asStateFlow()
    val accessGeneration: Long get() = generation.get()

    fun initialize(context: Context) { applicationContext = context.applicationContext }
    @Synchronized fun lock() { generation.incrementAndGet(); access.value = false }

    @Synchronized
    fun unlockAfterDeviceAuthentication() {
        lock()
        try {
            val context = applicationContext ?: throw VaultUnavailableException()
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) throw VaultUnavailableException()
            val key = key(create = true)
            // The OS-authenticated key must work, not just a successful UI callback.
            VaultCodec.encrypt("device authentication check", key)
            generation.incrementAndGet()
            access.value = true
        } catch (e: Exception) { throw VaultUnavailableException(e) }
    }

    private fun key(create: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            if (!create) throw VaultUnavailableException()
            val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(AUTH_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(builder.build())
            }.generateKey()
        }
        // Never silently replace an invalidated/lost key and discard existing records.
        return store.getKey(KEY_ALIAS, null) as? SecretKey ?: throw VaultUnavailableException()
    }

    override fun encrypt(plaintext: String): String = useKey { VaultCodec.encrypt(plaintext, it) }
    override fun decrypt(encryptedPayload: String): String = useKey { VaultCodec.decrypt(encryptedPayload, it) }

    private fun useKey(operation: (SecretKey) -> String): String {
        if (!access.value) throw VaultUnavailableException()
        return try { operation(key()) } catch (e: Exception) {
            lock()
            throw VaultUnavailableException(e)
        }
    }

    fun computeSha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
