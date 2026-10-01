package com.example.data.model

import android.graphics.Bitmap

data class ChatAttachment(
    val name: String,
    val type: String, // "IMAGE", "DOCUMENT", "CODE"
    val base64Data: String,
    val sizeBytes: Long,
    val previewBitmap: Bitmap? = null
)
