package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BiometricStatus { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNSUPPORTED }

class BiometricAuthManager(private val context: Context) {
    val isUnlocked: StateFlow<Boolean> = EncryptionManager.isUnlocked
    private val _biometricStatus = MutableStateFlow(BiometricStatus.UNSUPPORTED)
    val biometricStatus: StateFlow<BiometricStatus> = _biometricStatus.asStateFlow()
    val requiresLegacyDeviceCredential: Boolean get() = Build.VERSION.SDK_INT < 30
    init { EncryptionManager.initialize(context); checkBiometricAvailability() }

    fun checkBiometricAvailability(): BiometricStatus {
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        val status = if (secure) BiometricStatus.AVAILABLE else BiometricStatus.NONE_ENROLLED
        _biometricStatus.value = status
        return status
    }

    @Suppress("DEPRECATION")
    fun deviceCredentialIntent(): Intent? = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        .createConfirmDeviceCredentialIntent("Unlock Private AI", "Use this phone's screen lock to unlock protected data.")

    fun completeDeviceCredentialAuthentication(): Boolean = try {
        EncryptionManager.unlockAfterDeviceAuthentication()
        true
    } catch (_: Exception) { lock(); false }

    fun promptBiometricAuth(activity: FragmentActivity, title: String = "Unlock Private AI",
        subtitle: String = "Authenticate with the device screen lock or biometrics",
        onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (requiresLegacyDeviceCredential) {
            onError("Use the Android device screen lock."); return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (completeDeviceCredentialAuthentication()) onSuccess()
                    else onError("The protected vault key could not be unlocked. Existing data was preserved.")
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    lock(); onError("Device authentication did not complete.")
                }
                override fun onAuthenticationFailed() { onError("Device authentication was not verified.") }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build())
    }

    // Application-entered PINs are not Android screen-lock authentication.
    @Suppress("UNUSED_PARAMETER")
    fun unlockWithPin(pin: String): Boolean = false

    fun lock() { EncryptionManager.lock() }
}
