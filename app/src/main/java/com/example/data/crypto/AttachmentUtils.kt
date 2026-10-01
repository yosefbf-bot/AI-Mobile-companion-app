package com.example.data.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.data.model.ChatAttachment
import java.io.ByteArrayOutputStream

object AttachmentUtils {

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun base64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64Str, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToChatAttachment(bitmap: Bitmap, customName: String? = null): ChatAttachment {
        val base64 = bitmapToBase64(bitmap)
        val sizeBytes = base64.length * 3L / 4L
        val name = customName ?: "Camera_Photo_${System.currentTimeMillis() % 10000}.jpg"
        return ChatAttachment(
            name = name,
            type = "IMAGE",
            base64Data = base64,
            sizeBytes = sizeBytes,
            previewBitmap = bitmap
        )
    }

    fun uriToChatAttachment(context: Context, uri: Uri): ChatAttachment? {
        return try {
            val contentResolver = context.contentResolver
            var fileName = "attachment_${System.currentTimeMillis() % 10000}"
            var fileSize = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val type = when {
                mimeType.startsWith("image/") -> "IMAGE"
                mimeType.contains("pdf") || mimeType.contains("document") || mimeType.contains("text") -> "DOCUMENT"
                else -> "FILE"
            }

            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val bitmap = if (type == "IMAGE") {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null

            ChatAttachment(
                name = fileName,
                type = type,
                base64Data = base64,
                sizeBytes = if (fileSize > 0) fileSize else bytes.size.toLong(),
                previewBitmap = bitmap
            )
        } catch (e: Exception) {
            null
        }
    }
}
