package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.ui.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Real permission UI on a disposable OS-PIN device; not a camera sensor/QR claim. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PairingCameraPermissionAcceptanceTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = dispatcher)

    @After fun cleanup() {
        rule.activityRule.scenario.close()
        rule.runOnUiThread { dispatcher.scheduler.runCurrent() }
    }

    @Test fun scannerRequestsCameraOnlyAfterUserChoiceAndDenialKeepsManualPairing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("Use an explicitly configured disposable OS-PIN test device.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        val entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")
            .pkg(Pattern.compile("com\\.android\\.(systemui|settings)"))), 10_000)
        assertNotNull("Actual OS credential UI is required before scanner access.", entry)
        entry!!.text = pin!!
        device.pressEnter()
        rule.waitUntil(20_000) { EncryptionManager.isUnlocked.value &&
            rule.onAllNodesWithTag("onboarding_scan_qr").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(EncryptionManager.remainingSessionMillis() in 1..300_000L)
        assumeTrue("The permission-denial case starts on a fresh device without camera permission.",
            ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        rule.onNodeWithTag("onboarding_scan_qr").performClick()
        rule.onNodeWithTag("pairing_scanner").assertIsDisplayed()
        rule.onNodeWithTag("pairing_camera_permission").assertIsDisplayed()
        val denySelector = By.res(Pattern.compile("com\\.(google\\.)?android\\.permissioncontroller:id/permission_deny_button"))
        assertFalse("Opening the scanner alone must not request permission.", device.hasObject(denySelector))
        rule.onNodeWithTag("pairing_camera_permission").performClick()
        val deny = device.wait(Until.findObject(denySelector), 5_000)
        assertNotNull("The user's Allow camera choice must open Android permission UI.", deny)
        deny!!.click()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(
            "Camera permission was not granted. You can retry or use the secure manual link.").fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA))
        rule.onNodeWithTag("pairing_camera_cancel").performClick()
        rule.onNodeWithTag("pairing_scanner").assertDoesNotExist()
        rule.onNodeWithTag("node_settings_screen").assertIsDisplayed()
        lateinit var model: MainViewModel
        rule.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        assertNull("Denied scanning must own no camera resources.", model.stopPairingScanner)
        assertFalse(model.nodeConfig.value.isPaired)
        assertFalse(model.pairingState.value.connecting)
        // This fixed settings fixture's privacy item is index 6. Compose 1.7.2
        // performScrollTo and performScrollToNode both have unbounded retry
        // loops. Use a finite number of actual scroll actions below, requiring
        // visibility and progress rather than retrying until the heap is full.
        rule.onNodeWithTag("node_settings_screen").performScrollToIndex(6)
        settleSettingsScroll()
        listOf("10", "20", "30", "off").forEach { tag ->
            showSettingsControl("session_mode_" + tag, direction = 1f)
            rule.onNodeWithTag("session_mode_" + tag).assertIsNotEnabled()
        }
        showSettingsControl("session_mode_5", direction = -1f)
        rule.onNodeWithTag("session_mode_5").assertIsSelected()
        rule.onNodeWithTag("node_settings_screen").performScrollToIndex(2)
        settleSettingsScroll()
        showSettingsControl("scan_pairing_qr", direction = 1f)
        rule.onNodeWithTag("scan_pairing_qr").performClick()
        device.pressHome()
        rule.waitUntil(10_000) { rule.activityRule.scenario.state == Lifecycle.State.CREATED }
        assertFalse(EncryptionManager.hasCurrentAccess())
        assertEquals(0L, EncryptionManager.remainingSessionMillis())
        assertNull(model.stopPairingScanner)
        assertFalse(model.pairingState.value.connecting)
    }

    private fun showSettingsControl(tag: String, direction: Float) {
        val control = rule.onNodeWithTag(tag)
        val settings = rule.onNodeWithTag("node_settings_screen")
        repeat(6) { step ->
            try { control.assertIsDisplayed(); return }
            catch (notVisible: AssertionError) { if (step == 5) throw notVisible }
            val viewport = settings.fetchSemanticsNode()
            assertTrue("The real settings viewport must have positive height.", viewport.boundsInRoot.height > 0f)
            val before = viewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
            settings.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue("The actual settings scroll action must be accepted.",
                    scroll(0f, direction * viewport.boundsInRoot.height * 0.6f))
            }
            settleSettingsScroll()
            val after = settings.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("No scrolling progress while bringing $tag into view at bounded step $step (before=$before, after=$after).", before != after)
        }
        control.assertIsDisplayed()
    }

    private fun settleSettingsScroll() {
        // Compose 1.7.2 ScrollBy/ScrollToIndex enqueue coroutines and return before
        // scrolling. Its idler only advances time for frame/snapshot/recomposer
        // work, so the StandardTestDispatcher launch may not have started yet.
        // One frame runs queued work on the configured test scheduler; idle then
        // finishes the real scroll animation before measuring actual progress.
        // Official source: foundation-android 1.7.2 Scrollable.kt:520-524 and
        // LazyLayoutSemantics.kt:197-207; ui-test-android 1.7.2
        // ComposeIdlingResource.android.kt:59-78 (Google Maven source artifacts).
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }
}
