package com.example.data.model

/** Display receipt only. Credentials and the scanned link are deliberately absent. */
data class PairingCompletion(
    val serverName: String,
    val approvedScopes: Set<String>,
    val sharedChats: List<String>,
    val fingerprint: String,
    val notificationsWhileLocked: Boolean,
    val bootstrap: Boolean
)
