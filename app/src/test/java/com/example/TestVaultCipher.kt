package com.example

import com.example.data.crypto.VaultCipher
import com.example.data.crypto.VaultCodec
import com.example.data.crypto.VaultUnavailableException
import javax.crypto.KeyGenerator

/** Isolated software key tests the production codec, not Android authentication hardware. */
class TestVaultCipher : VaultCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var available = true
    override fun encrypt(plaintext: String): String {
        if (!available) throw VaultUnavailableException()
        return VaultCodec.encrypt(plaintext, key)
    }
    override fun decrypt(encryptedPayload: String): String {
        if (!available) throw VaultUnavailableException()
        return VaultCodec.decrypt(encryptedPayload, key)
    }
}
