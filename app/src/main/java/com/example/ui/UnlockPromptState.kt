package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class AuthenticationCompletion internal constructor(
    val attempt: Long, val completion: Long, val requestedSessionMinutes: Int?
)

/** Retained across rotation; never stores credentials or grants vault access. */
class UnlockPromptState : ViewModel() {
    private var automaticAttempted = false
    private var authenticated = false
    private var attemptSerial = 0L
    private var completionSerial = 0L
    private var requestedSessionMinutes: Int? = null
    private var activeCompletion: AuthenticationCompletion? = null
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

    /** Only a live secure/unlocked foreground settings action may request a policy. */
    fun prepareSessionChange(minutes: Int): Boolean {
        if (minutes !in setOf(5, 10, 20, 30) || promptInProgress || authenticated) return false
        requestedSessionMinutes = minutes
        return true
    }

    private fun beginAttempt(): Boolean {
        attemptSerial++
        activeCompletion = null
        automaticAttempted = true
        promptInProgress = true
        authenticated = false
        error = null
        return true
    }

    fun onAuthenticated() {
        if (promptInProgress) authenticated = true
    }

    fun beginCompletion(foregroundReady: Boolean): AuthenticationCompletion? {
        if (!authenticated || !promptInProgress || !foregroundReady || activeCompletion != null) return null
        return AuthenticationCompletion(attemptSerial, ++completionSerial, requestedSessionMinutes).also { activeCompletion = it }
    }

    fun isCurrentCompletion(ticket: AuthenticationCompletion): Boolean =
        authenticated && promptInProgress && ticket.attempt == attemptSerial && activeCompletion == ticket

    /** Defer this continuation while retaining the original OS success and timestamp. */
    fun deferCompletion(ticket: AuthenticationCompletion) {
        if (isCurrentCompletion(ticket)) activeCompletion = null
    }

    fun finishCompletion(ticket: AuthenticationCompletion): Boolean {
        if (!isCurrentCompletion(ticket)) return false
        activeCompletion = null
        requestedSessionMinutes = null
        authenticated = false
        promptInProgress = false
        error = null
        return true
    }

    fun failCompletion(ticket: AuthenticationCompletion, message: String): Boolean {
        if (!isCurrentCompletion(ticket)) return false
        onFailure(message, terminal = true)
        return true
    }

    // Compatibility for the existing pure prompt-state regression tests.
    fun takeAuthenticated(foregroundReady: Boolean): Boolean =
        beginCompletion(foregroundReady)?.let(::finishCompletion) ?: false

    fun onFailure(message: String, terminal: Boolean) {
        error = message
        if (terminal) {
            activeCompletion = null
            requestedSessionMinutes = null
            promptInProgress = false
            authenticated = false
        }
    }

    fun onStopped(changingConfigurations: Boolean) {
        if (!changingConfigurations && !promptInProgress) {
            automaticAttempted = false
            authenticated = false
            activeCompletion = null
            requestedSessionMinutes = null
        }
    }
}
