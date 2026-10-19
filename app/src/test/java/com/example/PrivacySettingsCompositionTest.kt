package com.example

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.ui.ScreenCapturePreferences
import com.example.ui.screens.settings.NodeSettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual settings interactions and layout; no production vault-unlock substitute. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp")
class PrivacySettingsCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = ScreenCapturePreferences(context)
    private val visible = mutableStateOf(true)
    private val settingsGeneration = mutableStateOf(0)
    private val clinical = mutableStateOf(true)
    private var clinicalChanges = 0
    private val phoneLock = mutableStateOf(com.example.data.crypto.DeviceScreenLockState.SECURE_UNLOCKED)
    private val committed = mutableStateOf(5)
    private val automaticDrafts = mutableStateOf(true)
    private val requested = mutableListOf<Int>()

    @Before fun setUp() {
        context.getSharedPreferences("screen_capture", Context.MODE_PRIVATE).edit().clear().commit()
        rule.mainClock.autoAdvance = false
    }
    private fun frame() { rule.mainClock.advanceTimeBy(300); rule.waitForIdle() }
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        // With autoAdvance disabled, waitForIdle does not deliver a recomposition frame.
        // Publish the test's snapshot writes before pumping the paused clock, including
        // a later frame for newly attached dialog/layout roots.
        repeat(120) {
            rule.runOnIdle { Snapshot.sendApplyNotifications() }
            frame()
            if (condition()) return
        }
        assertTrue(description, condition())
    }
    @After fun disposeAndClearFixture() {
        rule.runOnIdle { visible.value = false }
        frame()
        context.getSharedPreferences("screen_capture", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun render() {
        rule.setContent {
            MyApplicationTheme {
                if (visible.value) key(settingsGeneration.value) {
                    var allowed by remember { mutableStateOf(preferences.readAllowed()) }
                    NodeSettingsScreen(DesktopNodeConfig(), ConnectionStatus.DISCONNECTED, clinical.value,
                        onSaveConfig = {}, onToggleSecureClinicalMode = { clinical.value = it; clinicalChanges++ },
                        onTestConnection = {}, onLockVault = {}, allowScreenshots = allowed,
                        onAllowScreenshotsChange = { preferences.saveAllowed(it); allowed = it },
                        sessionMinutes = committed.value, screenLockState = phoneLock.value,
                        onRequestSessionMinutesChange = { requested.add(it) },
                        autoSaveDrafts = automaticDrafts.value, onAutoSaveDraftsChange = { automaticDrafts.value = it })
                }
            }
        }
        frame()
        showPrivacy()
    }
    private fun showPrivacy() {
        rule.onNodeWithTag("node_settings_screen").performScrollToIndex(6)
        frame()
    }

    @Test fun screenshotChoiceStartsOffAndSurvivesSettingsRecreation() {
        render()
        rule.onNodeWithTag("allow_screenshots_switch").assertIsOff().performClick()
        frame()
        assertTrue(preferences.readAllowed())
        // Recreate the settings subtree, including all remember state, within the live test host.
        rule.runOnIdle { settingsGeneration.value++ }
        frame()
        showPrivacy()
        rule.onNodeWithTag("allow_screenshots_switch").assertIsOn().performClick()
        frame()
        assertFalse(preferences.readAllowed())
    }

    @Test fun screenshotAndClinicalProcessingControlsAreIndependent() {
        render()
        rule.onNodeWithTag("allow_screenshots_switch").performClick()
        frame()
        assertTrue(clinical.value)
        assertEquals(0, clinicalChanges)
        rule.onNodeWithTag("clinical_processing_switch").performScrollTo().assertIsOn().performClick()
        frame()
        assertFalse(clinical.value)
        assertEquals(1, clinicalChanges)
        assertTrue(preferences.readAllowed())
        showPrivacy()
        rule.onNodeWithTag("allow_screenshots_switch").assertIsOn()
    }

    @Test fun durationRadioAndLabelCentersAlignWithoutEnablingUnsupportedSessions() {
        phoneLock.value = com.example.data.crypto.DeviceScreenLockState.NOT_CONFIGURED
        render()
        rule.onNodeWithTag("session_mode_5").assertIsSelected()
        val radio = rule.onNodeWithTag("session_radio_5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithTag("session_label_5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(radio.center.y, label.center.y, 1f)
        rule.onNodeWithTag("session_mode_10").assertIsNotEnabled().performClick()
        frame()
        rule.onNodeWithTag("session_mode_5").assertIsSelected()
        rule.onNodeWithTag("session_mode_10").assertIsNotSelected()
    }

    @Test fun allFourRowsWorkButOnlyCommittedPolicyMovesTheSelection() {
        render()
        listOf(10, 20, 30).forEach { minutes ->
            rule.onNodeWithTag("session_mode_" + minutes).performScrollTo().assertIsEnabled().performClick()
            frame()
            rule.onNodeWithTag("session_mode_5").assertIsSelected()
        }
        assertEquals(listOf(10, 20, 30), requested)
        rule.runOnIdle { committed.value = 20 }
        pumpUntil("Committed policy must select the 20-minute row.") {
            rule.onAllNodesWithTag("session_mode_20").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Selected) == true
        }
        rule.onNodeWithTag("session_mode_20").assertIsSelected()
        rule.onNodeWithTag("session_mode_5").performScrollTo().performClick(); frame()
        assertEquals(listOf(10, 20, 30, 5), requested)
        val radio = rule.onNodeWithTag("session_radio_20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithTag("session_label_20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(radio.center.y, label.center.y, 1f)
        rule.onNodeWithText("Off while active").assertDoesNotExist()
    }

    @Test fun missingLockedAndUnavailablePhoneSecurityDisablePolicyChanges() {
        render()
        listOf(com.example.data.crypto.DeviceScreenLockState.NOT_CONFIGURED,
            com.example.data.crypto.DeviceScreenLockState.SECURE_LOCKED,
            com.example.data.crypto.DeviceScreenLockState.UNAVAILABLE).forEach { state ->
            rule.runOnIdle { phoneLock.value = state }
            pumpUntil("The $state phone must disable session changes.") {
                rule.onAllNodesWithTag("session_mode_30").fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled) == true
            }
            rule.onNodeWithTag("session_mode_30").performScrollTo().assertIsNotEnabled().performClick(); frame()
            rule.onNodeWithTag("session_screen_lock_requirement").assertExists()
        }
        assertTrue(requested.isEmpty())
        rule.onNodeWithTag("session_mode_5").assertIsSelected()
    }

    @Test fun autoDraftToggleIsIndependentOfCaptureClinicalAndSessionChoices() {
        render()
        rule.onNodeWithTag("auto_save_drafts_switch").performScrollTo().assertIsOn().performClick(); frame()
        assertFalse(automaticDrafts.value)
        assertTrue(clinical.value)
        assertTrue(requested.isEmpty())
        assertEquals(5, committed.value)
        assertFalse(preferences.readAllowed())
        rule.onNodeWithTag("auto_save_drafts_switch").performClick(); frame()
        assertTrue(automaticDrafts.value)
    }
}
