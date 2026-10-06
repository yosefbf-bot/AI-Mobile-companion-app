package com.example

import android.app.KeyguardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultUnavailableException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real OS prompt and Android Keystore, only on an explicitly configured disposable test device. */
@RunWith(AndroidJUnit4::class)
class OsCredentialKeystoreAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun actualOsCredentialUnlocksKeystoreAndUnpairedWorkspaceThenRelocks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        assertTrue(instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        rule.onNodeWithTag("unlock_biometric_button").performClick()
        val device = UiDevice.getInstance(instrumentation)
        var entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        if (entry == null) {
            // A biometric-capable image can show its explicit device-credential fallback first.
            device.findObject(By.text("Use PIN"))?.click()
            entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        }
        assertNotNull("The real Android screen-lock credential field must appear.", entry)
        entry!!.text = pin!!
        device.pressEnter()
        rule.waitUntil(20_000) { EncryptionManager.isUnlocked.value }
        val unlockedGeneration = EncryptionManager.accessGeneration
        val protected = EncryptionManager.encrypt("Synthetic emulator acceptance note")
        assertEquals("Synthetic emulator acceptance note", EncryptionManager.decrypt(protected))
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_home").assertIsDisplayed()
        rule.onNodeWithText("Not an all-clear").assertExists()
        rule.onNodeWithText("All clear").assertDoesNotExist()
        rule.onNodeWithText("Running on PC").assertDoesNotExist()
        rule.onNodeWithTag("notes_followups_button").performClick()
        rule.onNodeWithTag("facility_capture_screen").assertIsDisplayed()
        // Closing application access must invalidate reads even inside the OS authentication window.
        EncryptionManager.lock()
        assertTrue(EncryptionManager.accessGeneration > unlockedGeneration)
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt(protected) }
        rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
    }
}
