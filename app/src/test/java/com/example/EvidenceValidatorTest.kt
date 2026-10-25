package com.example

import android.util.Base64
import com.example.data.crypto.EvidenceValidationException
import com.example.data.crypto.EvidenceValidator
import com.example.data.model.ChatAttachment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EvidenceValidatorTest {
    @Test fun importAndEnqueueUseTheSameJpegPngPdfAndTxtChecks() {
        val cases = listOf(
            Triple("synthetic.jpg", "image/jpeg", byteArrayOf(-1, -40, -1, -32)),
            Triple("synthetic.png", "image/png", byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
            Triple("synthetic.pdf", "application/pdf", "%PDF-1.7\nSYNTHETIC".toByteArray()),
            Triple("synthetic.txt", "text/plain", "SYNTHETIC UTF-8 text\n".toByteArray())
        )
        for ((name, mime, bytes) in cases) {
            val type = if (mime.startsWith("image/")) "IMAGE" else "DOCUMENT"
            val imported = EvidenceValidator.validateBytes(bytes, name, type, mime)
            val queued = EvidenceValidator.validate(ChatAttachment(name, type, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong()))
            assertEquals(mime, imported.mime); assertEquals(imported.mime, queued.mime)
            assertArrayEquals(imported.bytes, queued.bytes)
        }
    }

    @Test fun docxCsvWebpMismatchedMimeAndInvalidUtf8AreRejectedBeforeEnqueue() {
        for ((name, mime) in listOf("synthetic.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "synthetic.csv" to "text/csv", "synthetic.webp" to "image/webp")) {
            val bytes = "SYNTHETIC unsupported".toByteArray()
            assertThrows(EvidenceValidationException::class.java) { EvidenceValidator.validateBytes(bytes, name, "DOCUMENT", mime) }
            assertThrows(EvidenceValidationException::class.java) { EvidenceValidator.validate(
                ChatAttachment(name, "DOCUMENT", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong())) }
        }
        assertThrows(EvidenceValidationException::class.java) { EvidenceValidator.validateBytes(byteArrayOf(-1), "bad.txt", "DOCUMENT", "text/plain") }
        assertThrows(EvidenceValidationException::class.java) { EvidenceValidator.validateBytes("%PDF-1.7".toByteArray(), "bad.png", "IMAGE", "image/png") }
        assertThrows(EvidenceValidationException::class.java) { EvidenceValidator.validateBytes(byteArrayOf(0), "bad.txt", "DOCUMENT") }
    }
}
