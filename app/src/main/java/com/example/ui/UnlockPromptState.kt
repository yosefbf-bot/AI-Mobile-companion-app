package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Retained across rotation; never stores credentials or grants vault access. */
class UnlockPromptState : ViewModel() {
    private var automaticAttempted = false
    private var authenticated = false
    var promptInProgress by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun beginAutomatic(locked: Boolean, foregroundReady: Boolean): Boolean {
        if (!locked || !foregroundReady || automaticAttempted || promptInProgress) return false
        return beginAttempt()
    }

    fun beginRetry(locked: Boolean, foregroundReady: Boolean): Boolean {
        if (!locked || !foregroundReady || promptInProgress) return false
        return beginAttempt()
    }

    private fun beginAttempt(): Boolean {
        // Consume before launching Android UI: its own pause/resume must not retry it.
        automaticAttempted = true
        promptInProgress = true
        authenticated = false
        error = null
        return true
    }

    fun onAuthenticated() {
        if (promptInProgress) authenticated = true
    }

    fun takeAuthenticated(foregroundReady: Boolean): Boolean {
        if (!authenticated || !foregroundReady) return false
        authenticated = false
        promptInProgress = false
        return true
    }

    fun onFailure(message: String, terminal: Boolean) {
        error = message
        if (terminal) {
            promptInProgress = false
            authenticated = false
        }
    }

    fun onStopped(changingConfigurations: Boolean) {
        // Configuration changes and the device-credential Activity belong to the
        // same attempt. A terminal cancel/error leaves Retry until a later entry.
        if (!changingConfigurations && !promptInProgress) {
            automaticAttempted = false
            authenticated = false
        }
    }
}
