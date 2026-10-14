package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
        listOf("10", "20", "30", "off").forEach { tag ->
            rule.onNodeWithTag("node_settings_screen").performScrollToNode(hasTestTag("session_mode_" + tag))
            rule.onNodeWithTag("session_mode_" + tag).assertIsNotEnabled()
        }
        rule.onNodeWithTag("node_settings_screen").performScrollToNode(hasTestTag("session_mode_5"))
        rule.onNodeWithTag("session_mode_5").assertIsSelected()
        rule.onNodeWithTag("node_settings_screen").performScrollToNode(hasTestTag("scan_pairing_qr"))
        rule.onNodeWithTag("scan_pairing_qr").performClick()
        device.pressHome()
        rule.waitUntil(10_000) { rule.activityRule.scenario.state == Lifecycle.State.CREATED }
        assertFalse(EncryptionManager.hasCurrentAccess())
        assertEquals(0L, EncryptionManager.remainingSessionMillis())
        assertNull(model.stopPairingScanner)
        assertFalse(model.pairingState.value.connecting)
    }
}
