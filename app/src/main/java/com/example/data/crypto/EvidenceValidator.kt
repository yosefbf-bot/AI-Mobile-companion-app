package com.example.data.crypto

import android.util.Base64
import com.example.data.model.ChatAttachment
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class EvidenceValidationException(message: String) : IllegalArgumentException(message)
data class ValidatedEvidence(val bytes: ByteArray, val mime: String)

/** The same bounded format checks apply before import, durable enqueue and upload. */
object EvidenceValidator {
    const val SUPPORTED_FORMATS = "Supported evidence formats: JPEG, PNG, PDF, TXT (UTF-8 plain text). Images are stored for human review."
    val mimeTypes = setOf("image/jpeg", "image/png", "application/pdf", "text/plain")

    fun validate(attachment: ChatAttachment): ValidatedEvidence {
        if (attachment.base64Data.length > ((AttachmentUtils.MAX_ATTACHMENT_BYTES + 2) / 3) * 4)
            throw EvidenceValidationException("Evidence is limited to 5 MiB. $SUPPORTED_FORMATS")
        val bytes = try { Base64.decode(attachment.base64Data, Base64.NO_WRAP) }
            catch (_: IllegalArgumentException) { throw EvidenceValidationException(SUPPORTED_FORMATS) }
        return validateBytes(bytes, attachment.name, attachment.type)
    }

    fun validateBytes(bytes: ByteArray, name: String, type: String, declaredMime: String? = null): ValidatedEvidence {
        if (bytes.isEmpty() || bytes.size > AttachmentUtils.MAX_ATTACHMENT_BYTES)
            throw EvidenceValidationException("Evidence must contain between 1 byte and 5 MiB. $SUPPORTED_FORMATS")
        val mime = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
            bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
            type == "DOCUMENT" && name.endsWith(".txt", true) && isPlainText(bytes) -> "text/plain"
            else -> throw EvidenceValidationException(SUPPORTED_FORMATS)
        }
        if (declaredMime != null && (declaredMime !in mimeTypes || declaredMime != mime))
            throw EvidenceValidationException(SUPPORTED_FORMATS)
        return ValidatedEvidence(bytes, mime)
    }

    private fun isPlainText(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        bytes.none { (it.toInt() and 255) < 32 && it != 9.toByte() && it != 10.toByte() && it != 13.toByte() }
    } catch (_: Exception) { false }
}
