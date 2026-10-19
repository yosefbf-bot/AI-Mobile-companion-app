package com.example.data.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same authenticated codec for production Keystore keys and isolated test keys. */
object VaultCodec {
    const val PREFIX = "PAI2:"
    private const val IV_BYTES = 12
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val aad = "PrivateAI.AndroidVault.v2".toByteArray(Charsets.UTF_8)

    fun encrypt(value: String, key: SecretKey): String = encryptBound(value, key, PREFIX, aad)

    internal fun encryptBound(value: String, key: SecretKey, prefix: String, associatedData: ByteArray): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Vault input exceeds the supported size." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Keystore generates the nonce; callers never supply/reuse it.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == IV_BYTES) { "Unexpected authenticated cipher nonce." }
        cipher.updateAAD(associatedData)
        return prefix + Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    fun decrypt(value: String, key: SecretKey): String = decryptBound(value, key, PREFIX, aad)

    internal fun decryptBound(value: String, key: SecretKey, prefix: String, associatedData: ByteArray): String {
        require(value.startsWith(prefix) && value.length <= (MAX_BYTES + 32) * 4 / 3 + prefix.length) {
            "Unsupported or oversized vault payload."
        }
        val bytes = Base64.decode(value.substring(prefix.length), Base64.NO_WRAP)
        require(bytes.size >= IV_BYTES + 16) { "Invalid authenticated vault payload." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }
}
