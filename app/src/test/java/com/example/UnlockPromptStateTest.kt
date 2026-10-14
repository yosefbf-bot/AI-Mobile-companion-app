package com.example

import com.example.ui.UnlockPromptState
import org.junit.Assert.*
import org.junit.Test

class UnlockPromptStateTest {
    @Test fun waitsForLockedResumedFocusedForegroundBeforeConsumingTheEntry() {
        val state = UnlockPromptState()
        assertFalse(state.beginAutomatic(locked = true, foregroundReady = false))
        assertFalse(state.beginAutomatic(locked = false, foregroundReady = true))
        assertTrue(state.beginAutomatic(locked = true, foregroundReady = true))
        repeat(5) { assertFalse(state.beginAutomatic(locked = true, foregroundReady = true)) }
    }

    @Test fun canceledPromptDoesNotLoopAndRetryRequiresForeground() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onFailure("Canceled", terminal = true)
        assertFalse(state.promptInProgress)
        assertEquals("Canceled", state.error)
        repeat(5) { assertFalse(state.beginAutomatic(true, true)) }
        assertFalse(state.beginRetry(true, false))
        assertTrue(state.beginRetry(true, true))
        assertNull(state.error)
        assertFalse(state.beginRetry(true, true))
    }

    @Test fun rejectedBiometricKeepsTheSameSystemAttempt() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onFailure("Not verified", terminal = false)
        assertTrue(state.promptInProgress)
        assertFalse(state.beginRetry(true, true))
        assertFalse(state.beginAutomatic(true, true))
        state.onAuthenticated()
        assertTrue(state.takeAuthenticated(true))
    }

    @Test fun deviceCredentialStopAndCallbackWaitForResumedForegroundWithoutReprompting() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onStopped(changingConfigurations = false)
        state.onAuthenticated()
        assertFalse(state.takeAuthenticated(foregroundReady = false))
        assertTrue(state.promptInProgress)
        assertFalse(state.beginAutomatic(true, true))
        assertTrue(state.takeAuthenticated(foregroundReady = true))
        assertFalse(state.takeAuthenticated(true))
        // Expiry/manual lock within the same foreground entry is not a new launch.
        assertFalse(state.beginAutomatic(true, true))
    }

    @Test fun deviceCredentialCancellationOnReturnKeepsRetryUntilAnotherEntry() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onStopped(changingConfigurations = false)
        state.onFailure("Canceled", terminal = true)
        assertFalse(state.beginAutomatic(true, true))
        state.onStopped(changingConfigurations = false)
        assertTrue(state.beginAutomatic(true, true))
    }

    @Test fun rotationAfterCancellationDoesNotCreateAnotherAutomaticAttempt() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onFailure("Canceled", terminal = true)
        state.onStopped(changingConfigurations = true)
        assertFalse(state.beginAutomatic(true, true))
        assertEquals("Canceled", state.error)
        assertTrue(state.beginRetry(true, true))
    }

    @Test fun rotationDuringAuthenticationPreservesPendingSuccessAndSingleAttempt() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onStopped(changingConfigurations = true)
        assertFalse(state.beginAutomatic(true, true))
        state.onAuthenticated()
        assertFalse(state.takeAuthenticated(false))
        assertTrue(state.takeAuthenticated(true))
    }

    @Test fun ordinaryBackgroundEntryRearmsAfterCompletedAuthentication() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onAuthenticated()
        assertTrue(state.takeAuthenticated(true))
        state.onStopped(changingConfigurations = false)
        assertFalse(state.beginAutomatic(true, false))
        assertTrue(state.beginAutomatic(true, true))
    }

    @Test fun staleSuccessAfterTerminalErrorCannotGrantAccess() {
        val state = UnlockPromptState()
        assertTrue(state.beginAutomatic(true, true))
        state.onFailure("Interrupted", terminal = true)
        state.onAuthenticated()
        assertFalse(state.takeAuthenticated(true))
        assertFalse(state.beginAutomatic(true, true))
    }

    @Test fun processRecreationStartsLockedAuthenticationWithoutRestoringAnAccessGrant() {
        val prior = UnlockPromptState()
        assertTrue(prior.beginAutomatic(true, true))
        prior.onAuthenticated()
        val recreated = UnlockPromptState()
        assertFalse(recreated.takeAuthenticated(true))
        assertTrue(recreated.beginAutomatic(true, true))
    }
}
