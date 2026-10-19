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

    @Test fun confirmedDurationWaitsForRealSuccessAndForegroundBeforeSingleCompletion() {
        val state = UnlockPromptState()
        assertTrue(state.prepareSessionChange(20))
        assertNull(state.beginCompletion(true))
        assertTrue(state.beginRetry(true, true))
        state.onAuthenticated()
        assertNull(state.beginCompletion(false))
        val ticket = state.beginCompletion(true)!!
        assertEquals(20, ticket.requestedSessionMinutes)
        assertNull(state.beginCompletion(true))
        assertTrue(state.finishCompletion(ticket))
        assertFalse(state.finishCompletion(ticket))
        assertTrue(state.beginRetry(true, true)); state.onAuthenticated()
        assertNull(state.beginCompletion(true)!!.requestedSessionMinutes)
    }

    @Test fun pausedCompletionRetainsProofButOldFinallyCannotClearReplacement() {
        val state = UnlockPromptState()
        assertTrue(state.prepareSessionChange(10)); assertTrue(state.beginRetry(true, true)); state.onAuthenticated()
        val old = state.beginCompletion(true)!!
        state.deferCompletion(old)
        val replacement = state.beginCompletion(true)!!
        assertEquals(old.attempt, replacement.attempt)
        assertNotEquals(old.completion, replacement.completion)
        state.deferCompletion(old)
        assertFalse(state.failCompletion(old, "Old activity"))
        assertFalse(state.finishCompletion(old))
        assertEquals(10, replacement.requestedSessionMinutes)
        assertTrue(state.isCurrentCompletion(replacement))
        assertTrue(state.finishCompletion(replacement))
    }

    @Test fun terminalCancellationDiscardsDurationAndCannotFailANewerAttempt() {
        val state = UnlockPromptState()
        assertTrue(state.prepareSessionChange(30)); assertTrue(state.beginRetry(true, true)); state.onAuthenticated()
        val old = state.beginCompletion(true)!!
        state.onFailure("Canceled", terminal = true)
        state.onAuthenticated(); assertNull(state.beginCompletion(true))
        assertFalse(state.beginAutomatic(true, true))
        assertTrue(state.beginRetry(true, true)); state.onAuthenticated()
        val fresh = state.beginCompletion(true)!!
        assertNull(fresh.requestedSessionMinutes)
        assertFalse(state.failCompletion(old, "Late error"))
        assertTrue(state.finishCompletion(fresh)); assertNull(state.error)
    }

    @Test fun rotationAndCredentialStopPreserveOneRequestedPolicyWithoutReprompting() {
        val state = UnlockPromptState()
        assertTrue(state.prepareSessionChange(20)); assertTrue(state.beginRetry(true, true))
        state.onStopped(true); state.onStopped(false)
        assertFalse(state.beginAutomatic(true, true))
        state.onAuthenticated(); assertNull(state.beginCompletion(false))
        val ticket = state.beginCompletion(true)!!
        assertEquals(20, ticket.requestedSessionMinutes)
        assertTrue(state.finishCompletion(ticket))
    }

    @Test fun unsupportedAndConcurrentChoicesCannotReplaceAnActivePolicyRequest() {
        val state = UnlockPromptState()
        listOf(0, -1, 60).forEach { assertFalse(state.prepareSessionChange(it)) }
        assertTrue(state.prepareSessionChange(10)); assertTrue(state.beginRetry(true, true))
        assertFalse(state.prepareSessionChange(30))
        state.onAuthenticated(); state.onAuthenticated()
        assertEquals(10, state.beginCompletion(true)!!.requestedSessionMinutes)
        assertNull(state.beginCompletion(true))
    }
}
