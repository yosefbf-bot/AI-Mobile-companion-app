package com.example

import android.os.SystemClock
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultCodec
import com.example.data.crypto.VaultUnavailableException
import com.example.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.regex.Pattern
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Real 300-second OS-key expiry on the explicitly disposable CI device. */
@RunWith(AndroidJUnit4::class)
class ForegroundLeaseKeystoreAcceptanceTest {
    @Test fun actualFiveMinuteForegroundLeaseAndKeystoreAuthorizationBothExpire() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("privateai.test.devicePin")
        assumeTrue("Run only on an explicitly configured disposable OS-PIN test device.", !pin.isNullOrBlank())
        // A normal real Activity, without a virtual Compose coroutine clock: this
        // test must wait for actual elapsed time and actual Android authorization.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val device = UiDevice.getInstance(instrumentation)
            val entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")
                .pkg(Pattern.compile("com\\.android\\.(systemui|settings)"))), 10_000)
            assertNotNull("Actual OS authentication is required.", entry)
            entry!!.text = pin!!
            device.pressEnter()
            waitForRealTime(20_000) { EncryptionManager.isUnlocked.value }
            lateinit var model: MainViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            waitForRealTime(20_000) { model.vaultReady.value }
            val generation = EncryptionManager.accessGeneration
            val protected = EncryptionManager.encrypt("Synthetic foreground expiry acceptance")
            assertEquals("Synthetic foreground expiry acceptance", EncryptionManager.decrypt(protected))
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey("private_ai_vault_v2", null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertNull("The existing vault key must remain nonexportable.", key.encoded)
            assertTrue(info.isUserAuthenticationRequired)
            assertEquals(300, info.userAuthenticationValidityDurationSeconds)
            @Suppress("DEPRECATION")
            val securityLevel = if (Build.VERSION.SDK_INT >= 31) info.securityLevel.toString()
                else "insideSecureHardware=${info.isInsideSecureHardware}"
            Log.i("PrivateAILeaseAcceptance", "Original key metadata: authSeconds=300; securityLevel=$securityLevel; authEnforcedBySecureHardware=${info.isUserAuthenticationRequirementEnforcedBySecureHardware}")
            val remaining = EncryptionManager.remainingSessionMillis()
            assertTrue(remaining in 1..300_000L)
            val started = SystemClock.elapsedRealtime()
            Log.i("PrivateAILeaseAcceptance", "Waiting for real foreground expiry; remainingMillis=$remaining")
            waitForRealTime(remaining + 10_000L) {
                assertEquals("This must be continuous foreground expiry, not background lock.",
                    Lifecycle.State.RESUMED, scenario.state)
                !EncryptionManager.isUnlocked.value
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Access must not close early and be reported as a five-minute expiry.", elapsed >= remaining - 1000L)
            waitForRealTime(5_000) { !model.vaultReady.value }
            assertEquals(0L, EncryptionManager.remainingSessionMillis())
            assertTrue(EncryptionManager.accessGeneration > generation)
            assertThrows(VaultUnavailableException::class.java) { EncryptionManager.decrypt(protected) }
            assertThrows(VaultUnavailableException::class.java) { EncryptionManager.encrypt("Must remain locked") }
            // Read-only test-module lookup bypasses only the APPLICATION guard to
            // prove Android itself rejects this original key after real expiry.
            // No key is generated, changed, exported, deleted or replaced here.
            // Android 14 Keystore2 uses integer token-age seconds and rejects
            // only when age.seconds() > AUTH_TIMEOUT. Its 300.x second boundary
            // can therefore remain valid after our stricter millisecond lease.
            // AOSP android14-release: keystore2/src/enforcements.rs:651 and
            // keystore2/src/database.rs:789 (integer milliseconds / 1000):
            // https://android.googlesource.com/platform/system/security/+/android14-release/keystore2/src/enforcements.rs#651
            // https://android.googlesource.com/platform/system/security/+/android14-release/keystore2/src/database.rs#789
            // Cross that bounded platform boundary without authenticating again;
            // retain both direct OS rejection assertions rather than skip them.
            val platformBoundaryStarted = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - platformBoundaryStarted < 2_000L) {
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertFalse("The app must stay locked while crossing the OS boundary.", EncryptionManager.hasCurrentAccess())
                SystemClock.sleep(50)
            }
            assertFalse("No new OS authentication may extend the key authorization.", device.hasObject(
                By.clazz("android.widget.EditText").pkg(Pattern.compile("com\\.android\\.(systemui|settings)"))))
            Log.i("PrivateAILeaseAcceptance", "Direct original-key rejection probe; elapsedMillis=${SystemClock.elapsedRealtime() - started}; appAccess=false")
            assertThrows(UserNotAuthenticatedException::class.java) { VaultCodec.decrypt(protected, key) }
            assertThrows(UserNotAuthenticatedException::class.java) { VaultCodec.encrypt("Must require actual OS authentication", key) }
            assertTrue(store.containsAlias("private_ai_vault_v2"))
            scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            Log.i("PrivateAILeaseAcceptance", "Real five-minute expiry accepted; elapsedMillis=$elapsed; originalKeyPreserved=true")
        }
    }

    private fun waitForRealTime(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Actual elapsed-time acceptance condition did not complete.", condition())
    }
}
