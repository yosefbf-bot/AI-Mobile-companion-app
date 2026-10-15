package com.example.ui.pairing

import com.example.data.model.PairingCompletion

/** Ephemeral receipt/status only. The scanned link and tokens are never state. */
data class PairingUiState(
    val connecting: Boolean = false,
    val completion: PairingCompletion? = null,
    val error: String? = null
)
