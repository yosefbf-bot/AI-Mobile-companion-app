package com.example

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.crypto.DeviceScreenLock
import com.example.data.crypto.DeviceScreenLockState
import com.example.data.crypto.EncryptionManager
import com.example.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Runs BEFORE the disposable runner provisions a PIN. Never removes credentials or keys. */
@RunWith(AndroidJUnit4::class)
class SecureScreenLockAcceptanceTest {
    @Test fun phoneWithoutSecureScreenLockCannotUnlockOrEnableLongerSessions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val guard = context.getSystemService(KeyguardManager::class.java)
        assertFalse("This phase requires the genuinely unprotected fresh disposable emulator.", guard.isDeviceSecure)
        assertEquals(DeviceScreenLockState.NOT_CONFIGURED, DeviceScreenLock.read(context))
        fun protectedAliases(): List<String> = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .aliases().toList().filter { it.startsWith("private_ai_vault") }
        assertTrue("The pre-PIN phase must precede any protected key creation.", protectedAliases().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("The locked app must explain the required Android screen lock.",
                device.wait(Until.hasObject(By.textContains("screen lock")), 10_000))
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                assertFalse(model.isUnlocked.value)
                assertFalse(model.vaultReady.value)
                MainActivity::class.java.getDeclaredMethod("requestSessionChange", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, 30)
                assertFalse(EncryptionManager.hasCurrentAccess())
                assertEquals(5, EncryptionManager.committedSessionMinutes.value)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            assertFalse(device.hasObject(By.text("Privacy & Unlock")))
            assertTrue("A rejected settings callback must not create any protected key.", protectedAliases().isEmpty())
        }
    }
}
