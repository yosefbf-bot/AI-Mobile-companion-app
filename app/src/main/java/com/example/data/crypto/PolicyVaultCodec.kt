package com.example.data.crypto

import javax.crypto.SecretKey

/** Explicit key IDs, not caller-controlled Keystore aliases or trial decryption. */
internal object PolicyVaultCodec {
    const val PREFIX = "PAI3:"
    val MINUTES = setOf(5, 10, 20, 30)
    fun minutes(keyId: Int): Int = if (keyId == 0) 5 else keyId.also { require(it in MINUTES) }
    fun alias(keyId: Int): String = if (keyId == 0) "private_ai_vault_v2"
        else "private_ai_vault_v3_m${minutes(keyId)}"

    fun keyId(value: String): Int {
        if (value.startsWith(VaultCodec.PREFIX)) return 0
        require(value.startsWith(PREFIX)) { "Unsupported authenticated vault format." }
        val end = value.indexOf(':', PREFIX.length)
        require(end > PREFIX.length && end <= PREFIX.length + 2) { "Invalid vault key identifier." }
        val id = value.substring(PREFIX.length, end)
        require(id in setOf("5", "10", "20", "30")) { "Unknown vault key identifier." }
        return id.toInt()
    }

    fun encrypt(value: String, key: SecretKey, keyId: Int): String {
        if (keyId == 0) return VaultCodec.encrypt(value, key)
        val header = "$PREFIX${minutes(keyId)}:"
        return VaultCodec.encryptBound(value, key, header, "PrivateAI.AndroidVault.v3|$header".toByteArray(Charsets.UTF_8))
    }

    fun decrypt(value: String, key: SecretKey): String {
        val id = keyId(value)
        if (id == 0) return VaultCodec.decrypt(value, key)
        val header = "$PREFIX$id:"
        return VaultCodec.decryptBound(value, key, header, "PrivateAI.AndroidVault.v3|$header".toByteArray(Charsets.UTF_8))
    }
}
