package com.example

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.example.data.model.WorkflowSnapshot
import com.example.ui.screens.workflows.FacilityCaptureScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screen-only lifecycle reproduction; no production vault unlock or test bypass. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun notesCanBeRecomposedDisposedAndRestoredWithAnUnsentDraft() {
        val visible = MutableStateFlow(true)
        val busy = mutableStateOf(false)
        var savedForm = ""
        rule.setContent {
            MyApplicationTheme {
                val showing by visible.collectAsStateWithLifecycle()
                SideEffect { assertSame(Looper.getMainLooper(), Looper.myLooper()) }
                if (showing) FacilityCaptureScreen(
                    snapshot = WorkflowSnapshot(), busy = busy.value, error = null,
                    onCapture = { _, _, _, _, _, _, _ -> }, onRefresh = {},
                    onUpdateAction = { _, _, _, _, _ -> }, onSaveDraft = { _, _, _, _ -> },
                    onAcknowledge = {}, onBack = {},
                    loadUnsentDraft = { savedForm }, onUnsentDraftChange = { savedForm = it }
                ) else Text("Locked test surface")
            }
        }
        rule.onNodeWithTag("capture_note").performTextInput("Synthetic rounds note")
        // Draft serialization is a LaunchedEffect: let the queued frame complete
        // before testing removal/restoration of that saved draft.
        rule.waitUntil(5_000) { savedForm.contains("Synthetic rounds note") }
        repeat(10) {
            rule.runOnIdle { busy.value = true }
            rule.runOnIdle { busy.value = false }
            Thread { visible.value = false }.apply { start(); join() }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("facility_capture_screen").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("facility_capture_screen").assertDoesNotExist()
            Thread { visible.value = true }.apply { start(); join() }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("facility_capture_screen").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("facility_capture_screen").assertIsDisplayed()
            rule.onNodeWithTag("capture_note").assertTextContains("Synthetic rounds note")
        }
    }
}
