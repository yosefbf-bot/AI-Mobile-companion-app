package com.example

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.AlertCoverage
import com.example.data.model.ConnectionStatus
import com.example.data.network.DesktopNodeService
import com.example.ui.screens.alerts.AlertsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1000dp")
class AlertsCoverageCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun offlineUnknownDeniedDisabledAndIncompleteCoverageNeverBecomeAnAllClear() {
        val connection = mutableStateOf(ConnectionStatus.DISCONNECTED)
        val coverage = mutableStateOf(AlertCoverage())
        rule.setContent { MyApplicationTheme {
            AlertsScreen(emptyList(), connection.value, "synthetic", {}, { _, _ -> }, {}, {}, coverage = coverage.value)
        } }
        rule.onNodeWithTag("alert_coverage").assertTextContains("coverage unavailable", substring = true)
        rule.runOnIdle { connection.value = ConnectionStatus.CONNECTED }
        rule.onNodeWithTag("alert_coverage").assertTextContains("coverage is unknown", substring = true)
        rule.runOnIdle { coverage.value = AlertCoverage(scopeGranted = false) }
        rule.onNodeWithTag("alert_coverage").assertTextContains("not granted", substring = true)
        rule.runOnIdle { coverage.value = AlertCoverage(true, false, false, 0, false) }
        rule.onNodeWithTag("alert_coverage").assertTextContains("monitoring is disabled", substring = true)
        rule.runOnIdle { coverage.value = AlertCoverage(true, true, false, 200, true) }
        rule.onNodeWithTag("alert_coverage").assertTextContains("coverage is incomplete", substring = true)
        rule.onNodeWithTag("alert_coverage").assertTextContains("Desktop total: 200", substring = true)
        rule.onNodeWithTag("alert_coverage").assertTextContains("limited set", substring = true)
        rule.onNodeWithText("running normally", substring = true).assertDoesNotExist()
    }

    @Test fun localReadingAndFilteredEmptyListsDoNotChangeClinicalStatus() {
        val original = DesktopNodeService.parseAlerts(JSONObject("""{"alerts":[{"id":"synthetic","severity":"high","status":"open"}]}""")).single()
        val rows = mutableStateOf(listOf(original))
        val coverage = AlertCoverage(true, true, true, 1, false)
        rule.setContent { MyApplicationTheme {
            AlertsScreen(rows.value, ConnectionStatus.CONNECTED, "synthetic",
                onAcknowledgeAlert = { id -> rows.value = rows.value.map { if (it.id == id) it.copy(isRead = true, isAcknowledged = true) else it } },
                onApproveAction = { _, _ -> }, onRunAnalysis = {}, onMarkAllRead = {}, coverage = coverage)
        } }
        rule.onNodeWithText("Mark read locally").performClick()
        rule.onNodeWithText("Read on this phone").assertExists()
        rule.onNodeWithText("Unread (0)").performClick()
        rule.onNodeWithText("No Alerts in This Category").assertExists()
        rule.onNodeWithText("No cached alerts match this filter.", substring = true).assertExists()
        assertEquals(original.message, rows.value.single().message)
        assertTrue(rows.value.single().message.contains("Status: open"))
        rule.onNodeWithTag("alert_coverage").assertTextContains("does not resolve a clinical alert", substring = true)
        rule.onNodeWithText("unresolved", substring = true).assertDoesNotExist()
    }

    @Test fun absentAndMalformedCoverageFieldsStayUnknown() {
        assertEquals(AlertCoverage(scopeGranted = true), DesktopNodeService.parseAlertCoverage(JSONObject()))
        val parsed = DesktopNodeService.parseAlertCoverage(JSONObject().put("monitoringEnabled", "false")
            .put("isSurveillanceComplete", JSONObject.NULL).put("total", -1).put("truncated", "false"))
        assertEquals(AlertCoverage(scopeGranted = true), parsed)
    }
}
