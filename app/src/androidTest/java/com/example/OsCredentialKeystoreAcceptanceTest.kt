package com.example

import android.app.KeyguardManager
import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultUnavailableException
import com.example.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real OS prompt and Android Keystore, only on an explicitly configured disposable test device. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OsCredentialKeystoreAcceptanceTest {
    private val composeDispatcher = StandardTestDispatcher()
    // The unconfined default can resume composition on a repository IO emitter.
    // Queue through the Compose test clock so Android view work stays on main.
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = composeDispatcher)

    @After fun finishActivityAndDrainLifecycleCleanup() {
        // Compose 1.7.2's final runTest cleanup drains its scheduler on the test
        // thread. Destroy the real activity first, then finish canceled lifecycle
        // effects on Android main before that framework cleanup runs.
        rule.activityRule.scenario.close()
        rule.runOnUiThread { composeDispatcher.scheduler.runCurrent() }
    }

    private var phase = "startup"

    @Test fun actualOsCredentialUnlocksKeystoreAndUnpairedWorkspaceThenRelocks() {
        try {
            phase = "automatic startup prompt, cancellation and rotation"
            cancelAutomaticPromptAndCheckRetry()
            // Exercise disposal/recreation repeatedly; one passing transition did not
            // reproduce the intermittent lifecycle failure observed on the CI device.
            repeat(3) { round ->
                phase = "round ${round + 1}: authentication"
                Log.i("PrivateAIAcceptance", phase)
                if (round > 0) returnFromHome()
                unlockNavigateAndRelock(useRetry = round == 0)
            }
        } catch (failure: Throwable) {
            // Report before Compose rule teardown can obscure the original failure.
            // The disposable device contains synthetic acceptance data only.
            Log.e("PrivateAIAcceptance", "Failed at $phase", failure)
            throw failure
        }
    }

    private fun returnFromHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launchIntent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
        assertNotNull("The existing app launch intent must be available.", launchIntent)
        UiDevice.getInstance(instrumentation).pressHome()
        instrumentation.waitForIdleSync()
        instrumentation.targetContext.startActivity(launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun cancelAutomaticPromptAndCheckRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        // No app button tap: cold launch must display the real system prompt.
        assertNotNull("Opening a locked app must automatically display Android authentication.", credentialField(device))
        device.pressBack()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Try again").assertIsDisplayed()
        assertFalse(EncryptionManager.isUnlocked.value)
        rule.onNodeWithTag("nav_chat").assertDoesNotExist()
        assertFalse("Cancellation must not start another prompt.", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 1_000))
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        assertFalse("Rotation after cancellation must not start another prompt.", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 1_000))
    }

    private fun credentialField(device: UiDevice): androidx.test.uiautomator.UiObject2? {
        var entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        if (entry == null) {
            // A biometric-capable image can show its explicit credential fallback first.
            device.findObject(By.text("Use PIN"))?.click()
            entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        }
        return entry
    }

    private fun unlockNavigateAndRelock(useRetry: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        assertTrue(instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        if (useRetry) rule.onNodeWithTag("unlock_biometric_button").performClick()
        val device = UiDevice.getInstance(instrumentation)
        val entry = credentialField(device)
        assertNotNull("The real Android screen-lock credential field must appear.", entry)
        entry!!.text = pin!!
        device.pressEnter()
        rule.waitUntil(20_000) { EncryptionManager.isUnlocked.value }
        val unlockedGeneration = EncryptionManager.accessGeneration
        val protected = EncryptionManager.encrypt("Synthetic emulator acceptance note")
        assertEquals("Synthetic emulator acceptance note", EncryptionManager.decrypt(protected))
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_home").assertIsDisplayed()
        lateinit var model: MainViewModel
        rule.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            model.setSecureClinicalMode(false)
        }
        rule.waitUntil(20_000) { !model.secureClinicalMode.value && model.vaultReady.value }
        rule.activityRule.scenario.onActivity { activity ->
            assertTrue("Additional phone processing restrictions must not control protected-window capture.",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        // Chat is now the initial workspace; Dashboard remains explicit navigation.
        phase = "open Dashboard"
        rule.onNodeWithTag("nav_home").performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("dashboard_screen").fetchSemanticsNodes().isNotEmpty() }
        val coverageWarning = "No recorded alerts does not establish complete monitoring."
        rule.onNodeWithTag("dashboard_screen").performScrollToNode(hasText(coverageWarning))
        rule.onNodeWithText(coverageWarning).assertIsDisplayed()
        rule.onNodeWithText("All clear").assertDoesNotExist()
        rule.onNodeWithText("Running on PC").assertDoesNotExist()
        phase = "open Notes"
        rule.onNodeWithTag("notes_followups_button").performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("facility_capture_screen").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("facility_capture_screen").assertIsDisplayed()
        // Closing application access must invalidate reads even inside the OS authentication window.
        phase = "relock from Notes"
        EncryptionManager.lock()
        assertTrue(EncryptionManager.accessGeneration > unlockedGeneration)
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt(protected) }
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("biometric_lock_card").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
        rule.onNodeWithTag("facility_capture_screen").assertDoesNotExist()
        rule.activityRule.scenario.onActivity { activity ->
            assertTrue("The locked app and recents preview must remain protected.",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        Log.i("PrivateAIAcceptance", "Relock accepted")
    }
}
