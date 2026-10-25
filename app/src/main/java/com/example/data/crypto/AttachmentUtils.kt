package com.example.data.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.data.model.ChatAttachment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

/** Import bounded evidence; preview decoding cannot allocate the full resolution of a large image. */
object AttachmentUtils {
    const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 2048
    private const val MAX_IMAGE_PIXELS = 64_000_000L

    fun readBounded(stream: InputStream, limit: Int = MAX_ATTACHMENT_BYTES): ByteArray {
        require(limit in 1..MAX_ATTACHMENT_BYTES)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val value = stream.read()
                if (value < 0) break
                require(output.size() < limit) { "Attachment exceeds the 5 MiB limit." }
                output.write(value)
            } else {
                require(count <= limit - output.size()) { "Attachment exceeds the 5 MiB limit." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val scale = minOf(1.0, MAX_IMAGE_SIDE.toDouble() / max(bitmap.width, bitmap.height))
        val bounded = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap,
            max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true) else bitmap
        val stream = ByteArrayOutputStream()
        try {
            require(bounded.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 95), stream))
            require(stream.size() <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds the 5 MiB limit." }
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } finally { if (bounded !== bitmap) bounded.recycle() }
    }

    private fun decodePreview(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun base64ToBitmap(base64Str: String): Bitmap? = try {
        require(base64Str.length <= ((MAX_ATTACHMENT_BYTES + 2) / 3) * 4)
        val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
        require(bytes.size <= MAX_ATTACHMENT_BYTES)
        decodePreview(bytes)
    } catch (_: Exception) { null }

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(200).ifBlank { "attachment" }

    fun bitmapToChatAttachment(bitmap: Bitmap, customName: String? = null): ChatAttachment {
        val base64 = bitmapToBase64(bitmap)
        return ChatAttachment(name = safeName(customName ?: "Camera_Photo_${System.currentTimeMillis()}.jpg"),
            type = "IMAGE", base64Data = base64,
            sizeBytes = Base64.decode(base64, Base64.NO_WRAP).size.toLong(), previewBitmap = base64ToBitmap(base64))
    }

    fun uriToChatAttachment(context: Context, uri: Uri): ChatAttachment? {
        return try {
        require(uri.scheme == "content") { "Select an attachment through the system file picker." }
        val resolver = context.contentResolver
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex))
                    require(cursor.getLong(sizeIndex) <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds the 5 MiB limit." }
            }
        }
        val mime = resolver.getType(uri)?.lowercase() ?: return null
        if (mime !in EvidenceValidator.mimeTypes) return null
        val type = when {
            mime == "image/jpeg" || mime == "image/png" -> "IMAGE"
            else -> "DOCUMENT"
        }
        val bytes = resolver.openInputStream(uri)?.use { readBounded(it) } ?: return null
        EvidenceValidator.validateBytes(bytes, name, type, mime)
        val preview = if (type == "IMAGE") decodePreview(bytes) ?: return null else null
        ChatAttachment(name = safeName(name), type = type, base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            sizeBytes = bytes.size.toLong(), previewBitmap = preview)
        } catch (_: Exception) { null }
    }
}
