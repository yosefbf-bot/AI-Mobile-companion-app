package com.example

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.SecureConfigStore
import com.example.data.crypto.VaultUnavailableException
import com.example.data.local.AppDatabase
import com.example.data.model.ChatAttachment
import com.example.data.repository.PrivateAIRepository
import com.example.ui.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.regex.Pattern
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Genuine OS-authenticated elapsed time; no virtual lease, PIN bypass, key reset or deletion. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LongForegroundSessionAcceptanceTest {
    private val composeDispatcher = StandardTestDispatcher()
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = composeDispatcher)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val systemPackages = Pattern.compile("com\\.android\\.(systemui|settings)")
    private lateinit var model: MainViewModel
    private var phaseStarted = SystemClock.elapsedRealtime()
    private var phase = "startup"
        set(value) {
            Log.i("PrivateAILongAcceptance", "PHASE END (boundary): $field; elapsedMillis=${SystemClock.elapsedRealtime() - phaseStarted}")
            field = value
            phaseStarted = SystemClock.elapsedRealtime()
            Log.i("PrivateAILongAcceptance", "PHASE BEGIN: $value")
        }

    @After fun closeAndDrain() {
        rule.activityRule.scenario.close()
        rule.runOnUiThread { composeDispatcher.scheduler.runCurrent() }
    }

    private fun frame() { rule.mainClock.advanceTimeBy(300); rule.waitForIdle() }
    private fun unlock(manualRetry: Boolean = false) {
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assertFalse("A disposable OS-PIN fixture is required, not a skipped test.", pin.isNullOrBlank())
        if (manualRetry) {
            // Lock now intentionally requires the visible retry action. Core lock
            // flags can change before the queued Compose lock screen is rendered.
            frame()
            rule.waitUntil(10_000) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                val retry = rule.onAllNodesWithTag("unlock_biometric_button").fetchSemanticsNodes().singleOrNull()
                retry != null && !retry.config.contains(SemanticsProperties.Disabled) &&
                    rule.onNodeWithTag("unlock_biometric_button").isDisplayed()
            }
            rule.onNodeWithTag("unlock_biometric_button").assertIsDisplayed().assertIsEnabled().performClick()
        }
        var entry = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(systemPackages)), 3_000)
        if (entry == null) {
            if (!manualRetry && rule.onAllNodesWithTag("unlock_biometric_button").fetchSemanticsNodes().isNotEmpty())
                rule.onNodeWithTag("unlock_biometric_button").performClick()
            entry = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(systemPackages)), 5_000)
        }
        if (entry == null) {
            device.findObject(By.text("Use PIN").pkg(systemPackages))?.click()
            entry = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(systemPackages)), 10_000)
        }
        assertNotNull("Every new grant requires the genuine Android credential UI.", entry)
        entry!!.text = pin!!
        device.pressEnter()
        rule.waitUntil(30_000) { EncryptionManager.hasCurrentAccess() }
        rule.activityRule.scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
        rule.waitUntil(30_000) { model.vaultReady.value }
        frame()
    }
    private fun privacy() {
        rule.onNodeWithTag("nav_home").performClick()
        rule.onNodeWithTag("settings_icon_button").performClick()
        rule.onNodeWithTag("node_settings_screen").performScrollToIndex(6)
        frame()
    }
    private fun showSettingsControl(matcher: SemanticsMatcher, label: String) {
        val control = rule.onNode(matcher)
        val settings = rule.onNodeWithTag("node_settings_screen")
        // Reuse the finite real ScrollBy/progress pattern in the accepted camera
        // harness. Compose 1.7.2's recursive performScrollTo loop exhausted the
        // device heap at the final button inside this tall Privacy list item.
        repeat(8) { step ->
            try { control.assertIsDisplayed(); return }
            catch (notVisible: AssertionError) { if (step == 7) throw notVisible }
            val viewport = settings.fetchSemanticsNode()
            assertTrue("The real settings viewport must have positive height.", viewport.boundsInRoot.height > 0f)
            val before = viewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
            settings.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue("The actual settings scroll action must be accepted.",
                    scroll(0f, viewport.boundsInRoot.height * 0.6f))
            }
            // ScrollBy launches on the configured dispatcher; deliver a frame
            // before checking real layout and scrolling progress.
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val after = settings.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            Log.i("PrivateAILongAcceptance", "SCROLL: $label; step=$step; before=$before; after=$after; phase=$phase")
            assertTrue("No scrolling progress while bringing $label into view at bounded step $step.", before != after)
        }
        control.assertIsDisplayed()
    }
    private fun choose(minutes: Int) {
        privacy()
        showSettingsControl(hasTestTag("session_mode_" + minutes), "session_mode_" + minutes)
        rule.onNodeWithTag("session_mode_" + minutes).assertIsDisplayed().assertIsEnabled().performClick()
        unlock()
        rule.waitUntil(30_000) { EncryptionManager.committedSessionMinutes.value == minutes && model.vaultReady.value }
        frame()
    }
    private fun assertKey(alias: String, seconds: Int) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertNull("The protected key must remain nonexportable.", key.encoded)
        assertTrue(info.isUserAuthenticationRequired)
        assertEquals(seconds, info.userAuthenticationValidityDurationSeconds)
    }
    private fun assertLocked() {
        rule.waitUntil(10_000) { !EncryptionManager.hasCurrentAccess() && !model.vaultReady.value }
        assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("Must remain locked") }
        rule.activityRule.scenario.onActivity {
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }
    private fun unlockPhoneIfNeeded() {
        val guard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        if (!guard.isDeviceLocked) return
        device.wakeUp()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        assertTrue("Wait for the genuine phone PIN screen before entering the disposable fixture credential.",
            device.wait(Until.hasObject(By.res("com.android.systemui", "pinEntry")), 10_000))
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")!!
        pin.forEach { digit -> device.pressKeyCode(KeyEvent.KEYCODE_0 + (digit - '0')) }
        device.pressEnter()
        rule.waitUntil(10_000) { !guard.isDeviceLocked }
    }
    private fun relaunch() {
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
        unlock()
    }

    @Test fun actualLongerSessionPreservesAutoDraftsBeyondFiveMinutesAndRestoresDefaultPolicy() {
        var primaryFailure: Throwable? = null
        var captureId: String? = null
        var previousDraft: String? = null
        var expectedDraftText = "Synthetic unsent note survives policy and reopening"
        var evidenceHash: String? = null
        try {
            phase = "original five-minute OS authentication"
            unlock()
            assertEquals("Suite isolation starts at the original default policy.", 5, EncryptionManager.committedSessionMinutes.value)
            assertKey("private_ai_vault_v2", 300)
            phase = "original five-minute synthetic draft and saved/pending five-MiB evidence"
            previousDraft = runBlocking { model.repository.loadLocalDraft("capture").form }
            // Printable ASCII (32..126) is valid UTF-8. The previous modulus97
            // inserted standalone0x80 bytes, so the shared validator correctly
            // rejected this supposed text fixture before storage acceptance.
            val bytes = ByteArray(5 * 1024 * 1024) { (it % 95 + 32).toByte() }
            val evidence = ChatAttachment("synthetic-five-mib.txt", "DOCUMENT", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong())
            evidenceHash = EncryptionManager.computeSha256(evidence.base64Data)
            captureId = runBlocking { model.repository.createCapture("Synthetic saved pending evidence", attachment = evidence) }
            val destinationBefore = com.example.data.network.PairingConfigParser.destinationIdentity(model.nodeConfig.value)
            rule.onNodeWithTag("nav_home").performClick()
            rule.onNodeWithTag("notes_followups_button").performClick()
            rule.waitUntil(20_000) { rule.onNodeWithTag("capture_note").isDisplayed() }
            rule.onNodeWithTag("capture_note").performTextReplacement(expectedDraftText)
            rule.activityRule.scenario.onActivity { model.acceptPendingEvidence("capture", evidence) }
            frame()
            runBlocking { model.repository.flushLocalDrafts() }
            fun assertDurableDraft() {
                // Fresh repository/cache proves actual disk persistence and hydration, not retained UI/RAM.
                val context = instrumentation.targetContext
                val reopened = PrivateAIRepository(AppDatabase.getInstance(context), configStore = SecureConfigStore(context),
                    demoDatabase = AppDatabase.getDemoInstance(context))
                try {
                    runBlocking {
                        reopened.onUnlocked().join()
                        val form = JSONObject(reopened.loadLocalDraft("capture").form)
                        assertEquals(expectedDraftText, form.getString("note"))
                        val restored = form.getJSONObject("evidence")
                        assertEquals(5L * 1024 * 1024, restored.getLong("bytes"))
                        assertEquals(evidence.base64Data, restored.getString("data"))
                        val saved = reopened.getDocumentById(captureId!!).first()!!
                        assertEquals(captureId, saved.id)
                        val payload = JSONObject(EncryptionManager.decrypt(saved.encryptedContent))
                        assertEquals(evidence.base64Data, payload.getJSONObject("localEvidence").getString("data"))
                        assertEquals("CAPTURE_PENDING", saved.fileType)
                    }
                } finally { reopened.close() }
            }
            assertDurableDraft()
            phase = "fresh OS-authenticated ten-minute migration"
            choose(10)
            assertKey("private_ai_vault_v3_m10", 600)
            assertEquals(destinationBefore, com.example.data.network.PairingConfigParser.destinationIdentity(model.nodeConfig.value))
            assertDurableDraft()
            val protected = EncryptionManager.encrypt("Synthetic read/write beyond five minutes")
            val started = SystemClock.elapsedRealtime()
            phase = "continuous real foreground beyond the original 300-second key limit"
            while (SystemClock.elapsedRealtime() - started < 305_000L) {
                assertEquals(Lifecycle.State.RESUMED, rule.activityRule.scenario.state)
                assertTrue("The opted-in foreground session must remain authorized beyond five minutes.", EncryptionManager.hasCurrentAccess())
                assertFalse("No hidden OS authentication may renew this grant.", device.hasObject(By.text("Unlock Private AI").pkg(systemPackages)))
                SystemClock.sleep(250)
            }
            assertEquals("Synthetic read/write beyond five minutes", EncryptionManager.decrypt(protected))
            assertEquals("Synthetic new write after five minutes", EncryptionManager.decrypt(EncryptionManager.encrypt("Synthetic new write after five minutes")))
            assertDurableDraft()
            phase = "post-migration small-field save reuses the same five-MiB evidence"
            rule.onNodeWithTag("nav_home").performClick()
            rule.onNodeWithTag("notes_followups_button").performClick()
            rule.waitUntil(20_000) { rule.onNodeWithTag("capture_note").isDisplayed() }
            expectedDraftText = "Synthetic newer text after ten-minute migration and real five-minute boundary"
            rule.onNodeWithTag("capture_note").performTextReplacement(expectedDraftText)
            frame()
            runBlocking { model.repository.flushLocalDrafts() }
            assertDurableDraft()
            phase = "manual lock revokes the longer session"
            privacy()
            showSettingsControl(hasText("Lock phone workspace"), "Lock phone workspace")
            rule.onNodeWithText("Lock phone workspace").assertIsDisplayed().performClick()
            assertLocked(); unlock(manualRetry = true)
            phase = "background revokes the longer session"
            assertTrue(device.pressHome())
            rule.waitUntil(10_000) { !rule.activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED) }
            assertLocked(); relaunch()
            phase = "actual secure phone lock revokes the longer session"
            device.sleep()
            val guard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
            rule.waitUntil(10_000) { guard.isDeviceLocked }
            assertLocked()
            unlockPhoneIfNeeded()
            relaunch()
            assertDurableDraft()
        } catch (failure: Throwable) {
            primaryFailure = failure
            Log.e("PrivateAILongAcceptance", "Failed at $phase", failure)
            throw failure
        } finally {
            try {
                if (::model.isInitialized && EncryptionManager.committedSessionMinutes.value != 5) {
                    phase = "cleanup restores original five-minute policy through actual authentication and migration"
                    if (!EncryptionManager.hasCurrentAccess()) { unlockPhoneIfNeeded(); relaunch() }
                    choose(5)
                    assertKey("private_ai_vault_v2", 300)
                    assertEquals(5, EncryptionManager.committedSessionMinutes.value)
                }
                if (::model.isInitialized && (captureId != null || previousDraft != null)) {
                    if (!EncryptionManager.hasCurrentAccess()) { unlockPhoneIfNeeded(); relaunch() }
                    runBlocking {
                        // Remove only this test's synthetic saved row and restore its prior draft.
                        // Do not contaminate the ten original cases' exact record-count assertions.
                        if (primaryFailure == null && evidenceHash != null) {
                            val restored = JSONObject(model.repository.loadLocalDraft("capture").form)
                            assertEquals(expectedDraftText, restored.getString("note"))
                            assertEquals(evidenceHash, EncryptionManager.computeSha256(restored.getJSONObject("evidence").getString("data")))
                            val saved = model.repository.getDocumentById(captureId!!).first()!!
                            assertEquals("CAPTURE_PENDING", saved.fileType)
                            val body = JSONObject(EncryptionManager.decrypt(saved.encryptedContent))
                            assertEquals(evidenceHash, EncryptionManager.computeSha256(body.getJSONObject("localEvidence").getString("data")))
                        }
                        captureId?.let { model.repository.deleteDocument(it) }
                        previousDraft?.let { before ->
                            val current = model.repository.loadLocalDraft("capture")
                            if (com.example.ui.hasLocalDraftWork(before)) {
                                model.repository.updateLocalDraft(current.token, before, true)
                                model.repository.flushLocalDrafts()
                            } else model.repository.deleteLocalDraft(current.token)
                        }
                    }
                }
                Log.i("PrivateAILongAcceptance", "Normal-auth cleanup completed; original five-minute policy retained; no keys deleted.")
            } catch (cleanup: Throwable) {
                Log.e("PrivateAILongAcceptance", "Cleanup failed at $phase; do not accept later default-policy results.", cleanup)
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
