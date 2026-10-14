package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
    @Volatile private var authenticatedAtElapsed: Long? = null
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
    fun deviceCredentialIntent(): Intent? {
        authenticatedAtElapsed = null
        return (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .createConfirmDeviceCredentialIntent("Unlock Private AI", "Use this phone's screen lock to unlock protected data.")
    }

    @Synchronized fun recordDeviceAuthentication() {
        // Retained with MainViewModel across rotation/foreground deferral. A
        // duplicate callback cannot extend the grant observed for this attempt.
        if (authenticatedAtElapsed == null) authenticatedAtElapsed = SystemClock.elapsedRealtime()
    }

    fun completeDeviceCredentialAuthentication(): Boolean = try {
        val authenticatedAt = authenticatedAtElapsed ?: throw VaultUnavailableException()
        EncryptionManager.unlockAfterDeviceAuthentication(authenticatedAt)
        true
    } catch (_: Exception) { lock(); false }

    // Recreate early in onCreate so AndroidX rebinds the retained prompt's callback
    // after rotation. Report OS success first; the Activity checks foreground state
    // before actually granting application access through the Keystore check.
    fun createBiometricPrompt(activity: FragmentActivity, onAuthenticated: () -> Unit,
        onError: (String) -> Unit, onFailed: (String) -> Unit): BiometricPrompt =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    lock(); onError("Device authentication did not complete.")
                }
                // A rejected biometric is nonterminal: Android still owns this attempt.
                override fun onAuthenticationFailed() { onFailed("Device authentication was not verified.") }
            })

    fun promptBiometricAuth(prompt: BiometricPrompt, title: String = "Unlock Private AI",
        subtitle: String = "Authenticate with the device screen lock or biometrics") {
        authenticatedAtElapsed = null
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build())
    }

    // Application-entered PINs are not Android screen-lock authentication.
    @Suppress("UNUSED_PARAMETER")
    fun unlockWithPin(pin: String): Boolean = false

    fun lock() { EncryptionManager.lock() }
}
