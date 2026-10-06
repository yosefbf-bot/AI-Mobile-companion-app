package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AttachmentUtils
import com.example.data.crypto.EncryptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Private AI", appName)
    }

    @Test
    fun `test aes256 encryption and decryption roundtrip`() {
        val originalText = "Top secret prompt: Train local model on private patient data."
        val cipher = TestVaultCipher()
        val encrypted = cipher.encrypt(originalText)

        assertNotEquals(originalText, encrypted)
        assertTrue(encrypted.isNotBlank())

        val decrypted = cipher.decrypt(encrypted)
        assertEquals(originalText, decrypted)
    }

    @Test
    fun `test sha256 checksum integrity calculation`() {
        val content = "Private AI local document vault contents"
        val hash = EncryptionManager.computeSha256(content)
        assertEquals(64, hash.length) // SHA-256 hex string is 64 characters
    }

    @Test
    fun `test attachment bitmap encoding and decoding`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val base64 = AttachmentUtils.bitmapToBase64(bitmap)
        assertTrue(base64.isNotEmpty())

        val restored = AttachmentUtils.base64ToBitmap(base64)
        assertNotNull(restored)
        assertEquals(32, restored?.width)
        assertEquals(32, restored?.height)
    }
}
