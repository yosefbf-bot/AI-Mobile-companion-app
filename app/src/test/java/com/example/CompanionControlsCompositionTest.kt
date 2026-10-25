package com.example

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.entity.DesktopTask
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.data.network.DesktopNodeService
import com.example.ui.screens.tasks.TasksScreen
import com.example.ui.screens.settings.NodeSettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp")
class CompanionControlsCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun realGatewayTasksHaveNoLauncherCancelRetryOrApprovalControls() {
        val connection = mutableStateOf(ConnectionStatus.CONNECTED)
        val tasks = listOf(DesktopTask("running", "Synthetic running", "RUNNING"),
            DesktopTask("failed", "Synthetic failed", "FAILED"),
            DesktopTask("approval", "Synthetic approval", "NEEDS_APPROVAL", requiresApproval = true))
        rule.setContent { MyApplicationTheme {
            TasksScreen(tasks, connection.value, "synthetic", {}, {}, {}, {}, { _, _ -> }, {})
        } }
        rule.onNodeWithTag("start_agent_fab").assertDoesNotExist()
        listOf("Cancel", "Retry", "Approve", "Reject").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.runOnIdle { connection.value = ConnectionStatus.DEMO }
        rule.onNodeWithTag("start_agent_fab").assertExists()
    }

    @Test fun notificationPermissionIsRequestedOnceForEachEnableToggle() {
        val config = mutableStateOf(DesktopNodeConfig(notificationKey = "synthetic-notification-only-key"))
        var permissionRequests = 0
        var saves = 0
        rule.setContent { MyApplicationTheme {
            NodeSettingsScreen(config.value, ConnectionStatus.DISCONNECTED, true,
                onSaveConfig = {
                    // Activity owns permission requests after the protected save callback.
                    val newlyEnabled = it.backgroundNotificationsEnabled && !config.value.backgroundNotificationsEnabled
                    config.value = it; saves++
                    if (newlyEnabled) permissionRequests++
                }, onToggleSecureClinicalMode = {}, onTestConnection = {}, onLockVault = {})
        } }
        rule.onNodeWithTag("node_settings_screen").performScrollToIndex(3)
        rule.onNodeWithTag("background_notifications_toggle").assertIsOff().performClick()
        rule.waitForIdle()
        assertEquals(1, permissionRequests); assertEquals(1, saves)
        rule.onNodeWithTag("background_notifications_toggle").assertIsOn().performClick()
        rule.waitForIdle()
        assertEquals(1, permissionRequests); assertEquals(2, saves)
        rule.onNodeWithTag("background_notifications_toggle").performClick()
        rule.waitForIdle()
        assertEquals(2, permissionRequests); assertEquals(3, saves)
    }

    @Test fun knownGatewayFailuresExplainRecoveryWithoutSuggestingBypasses() {
        assertTrue(DesktopNodeService.errorMessage("COMPANION_PAUSED", 409).contains("Resume it"))
        assertTrue(DesktopNodeService.errorMessage("LOW_MEMORY", 503).contains("free memory"))
        assertTrue(DesktopNodeService.errorMessage("SPEECH_SETUP", 503).contains("offline speech runtime"))
        assertTrue(DesktopNodeService.errorMessage("INTERRUPTED", 409).contains("Review the conversation"))
    }
}
