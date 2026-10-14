package com.example

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.BiometricAuthManager
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultUnavailableException
import org.junit.Assert.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Executes Android UI and Keystore fail-closed behavior; successful OS authentication is a separate device acceptance gate. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VaultLockAcceptanceTest {
    private val composeDispatcher = StandardTestDispatcher()
    // The unconfined default can resume composition on a repository IO emitter.
    // Queue through the Compose test clock so Android view work stays on main.
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = composeDispatcher)

    @After fun finishActivityAndDrainLifecycleCleanup() {
        // Complete ActivityScenario destruction and queued lifecycle cancellations
        // on Android main before Compose 1.7.2 drains its test scope off main.
        rule.activityRule.scenario.close()
        rule.runOnUiThread { composeDispatcher.scheduler.runCurrent() }
    }

    @Test fun startupKeepsSensitiveNavigationUncomposedBehindDeviceAuthentication() {
        dismissAutomaticAuthentication()
        rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
        rule.onNodeWithTag("nav_home").assertDoesNotExist()
        rule.onNodeWithTag("nav_chat").assertDoesNotExist()
        rule.onNodeWithTag("pin_input_field").assertDoesNotExist()
        rule.activityRule.scenario.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test fun arbitraryPinAndMissingOsAuthenticationCannotReadTheVaultOnAndroid() {
        dismissAutomaticAuthentication()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        EncryptionManager.initialize(context); EncryptionManager.lock()
        val auth = BiometricAuthManager(context)
        assertFalse(auth.unlockWithPin("1234"))
        assertFalse(auth.isUnlocked.value)
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("Synthetic test content") }
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt("ENC:Synthetic plaintext") }
    }

    private fun dismissAutomaticAuthentication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertFalse("Opening Android authentication must not itself grant vault access.", EncryptionManager.isUnlocked.value)
        if (instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            val device = UiDevice.getInstance(instrumentation)
            val promptVisible = device.wait(Until.hasObject(By.text("Unlock Private AI")), 5_000) ||
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("The automatic real Android authentication prompt must appear.", promptVisible)
            device.pressBack()
        }
        // Device credential UI can stop/cover MainActivity. Assert the secure lock
        // screen only after cancellation has returned the Activity to foreground.
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(EncryptionManager.isUnlocked.value)
    }
}
