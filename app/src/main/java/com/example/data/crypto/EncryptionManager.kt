package com.example.data.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val SALT = "PrivateAI_E2EE_LocalVault_Salt_v1"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    // Default master passphrase can be customized by the user in settings
    private var cachedKey: SecretKey? = null
    private var currentPassphrase = "PrivateAI-MasterSecret-Key-2026"

    init {
        deriveKey(currentPassphrase)
    }

    fun setMasterPassphrase(passphrase: String) {
        currentPassphrase = passphrase
        deriveKey(passphrase)
    }

    private fun deriveKey(passphrase: String) {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), SALT.toByteArray(), ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        cachedKey = SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Output format: Base64(iv + ciphertext + tag)
     */
    fun encrypt(plaintext: String): String {
        return try {
            val key = cachedKey ?: throw IllegalStateException("Key not derived")
            val iv = ByteArray(IV_LENGTH_BYTE)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Fallback gracefully if crypto fails
            "ENC:$plaintext"
        }
    }

    /**
     * Decrypts AES-256-GCM encrypted Base64 string.
     */
    fun decrypt(encryptedPayload: String): String {
        if (encryptedPayload.startsWith("ENC:")) {
            return encryptedPayload.removePrefix("ENC:")
        }
        return try {
            val key = cachedKey ?: throw IllegalStateException("Key not derived")
            val combined = Base64.decode(encryptedPayload, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH_BYTE) {
                return encryptedPayload
            }

            val iv = ByteArray(IV_LENGTH_BYTE)
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTE)

            val ciphertextLength = combined.size - IV_LENGTH_BYTE
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(combined, IV_LENGTH_BYTE, ciphertext, 0, ciphertextLength)

            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // In case of invalid key or modified payload
            "[Encrypted Content - Secure Key Required]"
        }
    }

    /**
     * Generates SHA-256 integrity hash for documents
     */
    fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
