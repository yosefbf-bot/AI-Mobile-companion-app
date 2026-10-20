package com.example

import com.example.data.crypto.*
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Software test keys exercise the production authenticated codec, never OS authentication. */
internal class PolicyTestCipher : VaultCipher {
    private val keys = (listOf(0) + PolicyVaultCodec.MINUTES).associateWith {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
    var active = 0
    override fun encrypt(plaintext: String) = PolicyVaultCodec.encrypt(plaintext, keys.getValue(active), active)
    override fun decrypt(encryptedPayload: String) = PolicyVaultCodec.decrypt(encryptedPayload, keys.getValue(PolicyVaultCodec.keyId(encryptedPayload)))
    fun rewrite(value: String, target: Int): String {
        val plain = decrypt(value)
        return if (PolicyVaultCodec.keyId(value) == target) value else PolicyVaultCodec.encrypt(plain, keys.getValue(target), target)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyVaultCodecTest {
    @Test fun originalFormatAndAllSelectedPoliciesRoundTripWithoutReplacingKeys() {
        val cipher = PolicyTestCipher()
        val original = cipher.encrypt("SYNTHETIC original")
        assertTrue(original.startsWith("PAI2:"))
        for (minutes in listOf(5, 10, 20, 30)) {
            cipher.active = minutes
            val converted = cipher.rewrite(original, minutes)
            assertEquals(minutes, PolicyVaultCodec.keyId(converted))
            assertEquals("SYNTHETIC original", cipher.decrypt(converted))
            assertEquals(converted, cipher.rewrite(converted, minutes))
            assertEquals("SYNTHETIC original", cipher.decrypt(original))
        }
    }
    @Test fun changedHeaderFailsAuthenticationEvenWhenTheSameTestKeyIsSupplied() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = PolicyVaultCodec.encrypt("SYNTHETIC", key, 10)
        val forged = encrypted.replaceFirst("PAI3:10:", "PAI3:20:")
        assertThrows(Exception::class.java) { PolicyVaultCodec.decrypt(forged, key) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { PolicyVaultCodec.decrypt(encrypted, otherKey) }
    }
    @Test fun unknownOrNoncanonicalPolicyIdentifiersNeverChooseArbitraryAliases() {
        for (value in listOf("PAI3:05:a", "PAI3:0:a", "PAI3:300:a", "PAI3:off:a", "PAI3:../vault:a", "PAI3:10", "plain")) {
            assertThrows(IllegalArgumentException::class.java) { PolicyVaultCodec.keyId(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { PolicyVaultCodec.alias(99) }
    }
}
