package com.example

import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
        rule.onNodeWithTag("nav_home").assertDoesNotExist()
        rule.onNodeWithTag("nav_chat").assertDoesNotExist()
        rule.onNodeWithTag("pin_input_field").assertDoesNotExist()
        rule.activityRule.scenario.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test fun arbitraryPinAndMissingOsAuthenticationCannotReadTheVaultOnAndroid() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        EncryptionManager.initialize(context); EncryptionManager.lock()
        val auth = BiometricAuthManager(context)
        assertFalse(auth.unlockWithPin("1234"))
        assertFalse(auth.isUnlocked.value)
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("Synthetic test content") }
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt("ENC:Synthetic plaintext") }
    }
}
