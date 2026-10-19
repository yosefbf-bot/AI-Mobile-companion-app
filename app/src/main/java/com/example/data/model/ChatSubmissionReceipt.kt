package com.example.data.model

/** Durable local identity only; never contains the draft text or evidence bytes. */
data class ChatSubmissionReceipt(
    val sessionId: String,
    val id: String,
    val evidenceNote: Boolean,
    val status: String
)
