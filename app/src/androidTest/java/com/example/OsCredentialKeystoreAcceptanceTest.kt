package com.example

import android.app.KeyguardManager
import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import com.example.data.model.ChatAttachment
import androidx.compose.ui.test.assertTextContains
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultUnavailableException
import com.example.data.crypto.VaultRecords
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.local.entity.EncryptedChatMessage
import com.example.ui.MainViewModel
import com.example.ui.UnlockPromptState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

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
    private val systemAuthenticationPackages = Pattern.compile("com\\.android\\.(systemui|settings)")
    private fun systemCredentialSelector() = By.clazz("android.widget.EditText").pkg(systemAuthenticationPackages)
    private fun systemPromptSelector() = By.text("Unlock Private AI").pkg(systemAuthenticationPackages)

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

    @Test fun actualOsCredentialRetainsDesktopSharedChatCacheAcrossUnlocks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        val dao = AppDatabase.getInstance(instrumentation.targetContext).chatDao()
        val sessionId = "synthetic-shared-preview-regression"
        val title = "Synthetic retained desktop chat"
        lateinit var model: MainViewModel
        var retained: EncryptedChatSession? = null
        try {
            cancelAutomaticPromptAndCheckRetry()
            repeat(3) { round ->
                phase = "shared chat round ${round + 1}: actual OS authentication"
                if (round == 0) rule.onNodeWithTag("unlock_biometric_button").performClick()
                else returnFromHome()
                val entry = credentialField(device)
                assertNotNull("The existing shared cache still requires real OS authentication.", entry)
                entry!!.text = pin!!
                device.pressEnter()
                rule.waitUntil(20_000) { EncryptionManager.hasCurrentAccess() }
                rule.activityRule.scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
                rule.waitUntil(20_000) { model.vaultReady.value }
                if (round == 0) {
                    // Exact retained row shape created by desktop sync: encrypted
                    // metadata plus a legitimate absent-preview sentinel. No real
                    // pairing, network, account or user records are used here.
                    retained = VaultRecords.encode(EncryptedChatSession(sessionId, title,
                        isSynced = true, desktopChatId = "synthetic-desktop-chat", desktopRevision = 1), EncryptionManager)
                    assertEquals("", retained!!.lastMessagePreviewEncrypted)
                    assertNotEquals(title, retained!!.title)
                    runBlocking { dao.insertSession(retained!!) }
                }
                phase = "shared chat round ${round + 1}: retained list and navigation"
                rule.waitUntil(20_000) { rule.onNodeWithText(title).isDisplayed() }
                rule.onNodeWithText(title).assertIsDisplayed()
                rule.waitForIdle()
                assertTrue("Rendering a shared chat with no preview must not relock the vault.", EncryptionManager.hasCurrentAccess())
                assertEquals("Retained ciphertext and absent preview must remain unchanged.", retained,
                    runBlocking { dao.allSessionsForMigration().single { it.id == sessionId } })
                val generation = EncryptionManager.accessGeneration
                rule.onNodeWithTag("chat_session_item_$sessionId").performClick()
                // The click updates lifecycle-collected StateFlow; wait for the
                // real destination to be placed before retaining its assertion.
                rule.waitUntil(20_000) { rule.onNodeWithTag("chat_detail_screen").isDisplayed() }
                rule.onNodeWithTag("chat_detail_screen").assertIsDisplayed()
                rule.onNodeWithTag("chat_detail_back_button").performClick()
                rule.waitUntil(20_000) {
                    rule.onNodeWithTag("chat_list_screen").isDisplayed() && rule.onNodeWithText(title).isDisplayed()
                }
                rule.onNodeWithText(title).assertIsDisplayed()
                assertEquals("Ordinary list navigation must not replace the authenticated session.", generation,
                    EncryptionManager.accessGeneration)
                if (round < 2) {
                    rule.activityRule.scenario.onActivity { model.lockVault() }
                    rule.waitUntil(10_000) { !EncryptionManager.isUnlocked.value }
                    rule.waitUntil(10_000) { rule.onNodeWithTag("biometric_lock_card").isDisplayed() }
                    rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
                }
            }
            phase = "shared chat: malformed nonempty preview retains fail-closed lock"
            runBlocking { dao.insertSession(retained!!.copy(lastMessagePreviewEncrypted = "invalid-nonempty-preview")) }
            rule.waitUntil(10_000) { !EncryptionManager.isUnlocked.value }
            rule.waitUntil(10_000) { rule.onNodeWithTag("biometric_lock_card").isDisplayed() }
            rule.onNodeWithTag("biometric_lock_card").assertIsDisplayed()
            assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("Synthetic locked probe") }
        } catch (failure: Throwable) {
            Log.e("PrivateAIAcceptance", "Failed at $phase", failure)
            throw failure
        } finally {
            // Remove only this test's synthetic cache, never the whole vault.
            runBlocking { dao.deleteMessagesForSession(sessionId); dao.deleteSession(sessionId) }
            EncryptionManager.lock()
        }
    }

    @Test fun realKeyboardKeepsComposerAndHistoryVisibleWithoutLosingDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        val dao = AppDatabase.getInstance(instrumentation.targetContext).chatDao()
        val sessionId = "synthetic-keyboard-layout"
        val messageId = "$sessionId-history"
        lateinit var model: MainViewModel
        try {
            phase = "keyboard: actual OS authentication"
            val entry = credentialField(device)
            assertNotNull("The keyboard layout test still requires the real OS unlock.", entry)
            entry!!.text = pin!!
            device.pressEnter()
            rule.waitUntil(20_000) { EncryptionManager.hasCurrentAccess() }
            rule.activityRule.scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            rule.waitUntil(20_000) { model.vaultReady.value }
            val retained = VaultRecords.encode(EncryptedChatMessage(messageId, sessionId, "user",
                EncryptionManager.encrypt("Synthetic earlier message"), status = "SYNCED_DESKTOP"), EncryptionManager)
            runBlocking {
                dao.insertSession(VaultRecords.encode(EncryptedChatSession(sessionId, "Synthetic keyboard chat"), EncryptionManager))
                dao.insertMessage(retained)
            }
            rule.waitUntil(20_000) { rule.onNodeWithTag("chat_session_item_$sessionId").isDisplayed() }
            rule.onNodeWithTag("chat_session_item_$sessionId").performClick()
            rule.waitUntil(20_000) { rule.onNodeWithTag("chat_message_input").isDisplayed() }
            phase = "keyboard: actual IME, bounded multiline composer and visible history"
            rule.onNodeWithTag("chat_message_input").performClick()
            rule.waitUntil(10_000) { keyboardBottomInset() > 0 }
            val draft = (1..12).joinToString("\n") { "Synthetic draft line $it" }
            rule.onNodeWithTag("chat_message_input").performTextInput(draft)
            rule.waitUntil(10_000) {
                val keyboardTop = rule.activity.window.decorView.height - keyboardBottomInset()
                rule.onNodeWithTag("chat_message_input").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 1f &&
                    rule.onNodeWithTag("chat_send_button").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 1f
            }
            rule.onNodeWithTag("chat_message_input").assertTextContains(draft)
            rule.onNodeWithTag("chat_send_button").assertIsDisplayed()
            rule.onNodeWithTag("chat_message_card_$messageId").assertIsDisplayed()
            val inputBounds = rule.onNodeWithTag("chat_message_input").fetchSemanticsNode().boundsInWindow
            val historyBounds = rule.onNodeWithTag("chat_message_card_$messageId").fetchSemanticsNode().boundsInWindow
            assertTrue("Recent history must stay above the composer while typing.", historyBounds.bottom <= inputBounds.top)
            assertTrue("Long drafts must scroll inside the four-line composer, not fill the window.",
                inputBounds.height <= 160f * rule.activity.resources.displayMetrics.density)
            assertEquals("Typing must not modify or duplicate retained history.", retained,
                runBlocking { dao.messageById(messageId) })
            assertEquals(1, runBlocking { dao.allMessagesForMigration().count { it.sessionId == sessionId } })
            assertTrue(device.pressBack())
            rule.waitUntil(10_000) { keyboardBottomInset() == 0 }
            rule.onNodeWithTag("chat_detail_screen").assertIsDisplayed()
            rule.onNodeWithTag("chat_message_input").assertTextContains(draft)
            rule.onNodeWithTag("chat_message_card_$messageId").assertIsDisplayed()
            assertTrue("Normal typing must not relock the protected workspace.", EncryptionManager.hasCurrentAccess())
        } catch (failure: Throwable) {
            Log.e("PrivateAIAcceptance", "Failed at $phase", failure)
            throw failure
        } finally {
            runBlocking { dao.deleteMessagesForSession(sessionId); dao.deleteSession(sessionId) }
            EncryptionManager.lock()
        }
    }

    @Test fun actualOsUnlockChatEvidenceSaveShowsFeedbackAndClearsOnlyDurablySavedDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        val database = AppDatabase.getInstance(instrumentation.targetContext)
        val sessionId = "synthetic-chat-evidence-save"
        lateinit var model: MainViewModel
        var captureId: String? = null
        val feedback = "Evidence note saved on this phone in Notes/Library; pending desktop delivery and review. Image contents were not interpreted."
        try {
            phase = "chat evidence: actual OS authentication"
            val entry = credentialField(device)
            assertNotNull("Saving chat evidence requires real OS authentication.", entry)
            entry!!.text = pin!!
            device.pressEnter()
            rule.waitUntil(20_000) { EncryptionManager.hasCurrentAccess() }
            rule.activityRule.scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            rule.waitUntil(20_000) { model.vaultReady.value }
            assertFalse("This disposable-device case must remain unpaired and account-free.", model.nodeConfig.value.isPaired)
            runBlocking { database.chatDao().insertSession(VaultRecords.encode(
                EncryptedChatSession(sessionId, "Synthetic chat evidence"), EncryptionManager)) }
            rule.waitUntil(20_000) { rule.onNodeWithTag("chat_session_item_$sessionId").isDisplayed() }
            rule.onNodeWithTag("chat_session_item_$sessionId").performClick()
            rule.waitUntil(20_000) { rule.onNodeWithTag("chat_message_input").isDisplayed() }
            rule.activityRule.scenario.onActivity {
                model.acceptPendingEvidence("chat:$sessionId", ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9))
            }
            rule.waitUntil(10_000) { rule.onNodeWithTag("attachment_preview_card").isDisplayed() }
            rule.onNodeWithTag("chat_message_input").performTextInput("Synthetic evidence note")
            phase = "chat evidence: durable offline save and visible feedback"
            rule.onNodeWithTag("chat_send_button").performClick()
            rule.waitUntil(10_000) { model.lastSavedChatSubmission.value != null && !model.isStreamingResponse.value }
            captureId = model.lastSavedChatSubmission.value!!.id
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
            rule.onNodeWithText(feedback).assertIsDisplayed()
            rule.onNodeWithTag("attachment_preview_card").assertDoesNotExist()
            rule.onNodeWithTag("chat_message_input").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            rule.onNodeWithTag("chat_send_button").assertIsNotEnabled()
            val document = runBlocking { model.repository.documents.first().single { it.id == captureId } }
            assertEquals("CAPTURE_PENDING", document.fileType)
            val payload = org.json.JSONObject(EncryptionManager.decrypt(document.encryptedContent))
            assertEquals("Synthetic evidence note", payload.getString("text"))
            assertEquals("c3ludGhldGlj", payload.getJSONObject("localEvidence").getString("data"))
            assertTrue(runBlocking { database.chatDao().getMessagesForSession(sessionId).first().isEmpty() })

            phase = "chat evidence: failed local submission retains draft with chat feedback"
            val failedDraft = "x".repeat(12001)
            rule.onNodeWithTag("chat_message_input").performTextReplacement(failedDraft)
            rule.onNodeWithTag("chat_send_button").performClick()
            val failure = "This message is too long for the desktop. Send shorter sections of up to 12,000 characters."
            rule.waitUntil(10_000) { model.chatSubmissionFinished.value >= 2 }
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
            rule.onNodeWithText(failure).assertIsDisplayed()
            rule.onNodeWithTag("chat_message_input").assertTextContains(failedDraft)
            rule.onNodeWithTag("chat_send_button").assertIsEnabled()
            assertEquals(1, runBlocking { model.repository.documents.first().count { it.id == captureId } })
            assertTrue(runBlocking { database.chatDao().getMessagesForSession(sessionId).first().isEmpty() })
            EncryptionManager.lock()
            rule.waitUntil(10_000) { rule.onNodeWithTag("biometric_lock_card").isDisplayed() }
            rule.onNodeWithText(failure).assertDoesNotExist()
            rule.onNodeWithTag("chat_detail_screen").assertDoesNotExist()
        } catch (failure: Throwable) {
            Log.e("PrivateAIAcceptance", "Failed at $phase", failure)
            throw failure
        } finally {
            runBlocking {
                captureId?.let { database.documentDao().deleteDocument(it) }
                database.chatDao().deleteMessagesForSession(sessionId)
                database.chatDao().deleteSession(sessionId)
            }
            EncryptionManager.lock()
        }
    }

    private fun keyboardBottomInset(): Int {
        var bottom = 0
        rule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        }
        return bottom
    }

    private fun returnFromHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launchIntent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
        assertNotNull("The existing app launch intent must be available.", launchIntent)
        assertTrue("Home must reach the real launcher.", UiDevice.getInstance(instrumentation).pressHome())
        // Idle main work does not establish that the Home animation delivered
        // onStop. A fast relaunch can instead produce only pause/resume, which
        // never leaves the existing authentication entry. Observe the real stop;
        // do not force an artificial ActivityScenario lifecycle transition.
        rule.waitUntil(10_000) {
            rule.activityRule.scenario.state == Lifecycle.State.CREATED
        }
        assertFalse("The stopped workspace must remain locked.", EncryptionManager.isUnlocked.value)
        Log.i("PrivateAIAcceptance", "Home background stop observed before relaunch")
        instrumentation.targetContext.startActivity(launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun cancelAutomaticPromptAndCheckRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("A disposable emulator OS credential must be explicitly supplied.", !pin.isNullOrBlank())
        val device = UiDevice.getInstance(instrumentation)
        // No app button tap: cold launch must display the real system prompt.
        assertNotNull("Opening a locked app must automatically display Android authentication.", credentialField(device))
        cancelSystemAuthentication(device)
        lateinit var promptState: UnlockPromptState
        rule.activityRule.scenario.onActivity { activity ->
            promptState = ViewModelProvider(activity)[UnlockPromptState::class.java]
        }
        rule.waitUntil(10_000) { !promptState.promptInProgress }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Try again").assertIsDisplayed()
        rule.onNodeWithTag("unlock_biometric_button").assertIsEnabled()
        assertFalse(EncryptionManager.isUnlocked.value)
        rule.onNodeWithTag("nav_chat").assertDoesNotExist()
        assertNoSystemAuthentication(device, "Cancellation must not start another prompt.")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        assertNoSystemAuthentication(device, "Rotation after cancellation must not start another prompt.")
    }

    private fun cancelSystemAuthentication(device: UiDevice) {
        val cancel = device.findObject(By.text("Cancel").pkg(systemAuthenticationPackages))
        if (cancel != null) cancel.click()
        else {
            // API 34's credential keyboard consumes the first Back. Only send a
            // second Back if the original Android credential window is still open.
            device.pressBack()
            if (!device.wait(Until.gone(systemCredentialSelector()), 2_000)) device.pressBack()
        }
        assertTrue("Cancel must close the actual Android credential field.",
            device.wait(Until.gone(systemCredentialSelector()), 5_000))
        assertTrue("Cancel must close the actual Android authentication window.",
            device.wait(Until.gone(systemPromptSelector()), 5_000))
    }

    private fun assertNoSystemAuthentication(device: UiDevice, message: String) {
        assertFalse(message, device.wait(Until.hasObject(systemCredentialSelector()), 1_000))
        assertFalse(message, device.wait(Until.hasObject(systemPromptSelector()), 1_000))
    }

    private fun credentialField(device: UiDevice): androidx.test.uiautomator.UiObject2? {
        var entry = device.wait(Until.findObject(systemCredentialSelector()), 5_000)
        if (entry == null) {
            // A biometric-capable image can show its explicit credential fallback first.
            device.findObject(By.text("Use PIN").pkg(systemAuthenticationPackages))?.click()
            entry = device.wait(Until.findObject(systemCredentialSelector()), 10_000)
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
        assertFalse("This case requires the account-free unpaired fixture.", model.nodeConfig.value.isPaired)
        assertTrue("The unpaired fixture has no action rows before its alerts item.", model.workflowState.value.actions.isEmpty())
        // The known empty fixture's alerts item is index 2. Avoid the framework's
        // recursive search/scroll loop, which exhausted the small CI heap after
        // the additional onboarding header reduced the viewport. The real exact
        // warning still has to be brought into view and asserted below.
        rule.onNodeWithTag("dashboard_screen").performScrollToIndex(2)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(coverageWarning).fetchSemanticsNodes().isNotEmpty() }
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
