package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
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
    private val authenticationAttempt = AtomicLong()
    private val callbackRegistration = AtomicLong()
    private val pendingPromptAttempt = AtomicLong(-1)
    private val promptOwners = java.util.WeakHashMap<BiometricPrompt, AtomicLong>()
    private val usedPrompts = java.util.WeakHashMap<BiometricPrompt, Boolean>()
    val biometricStatus: StateFlow<BiometricStatus> = _biometricStatus.asStateFlow()
    val requiresLegacyDeviceCredential: Boolean get() = Build.VERSION.SDK_INT < 30
    init { EncryptionManager.initialize(context); checkBiometricAvailability() }

    fun checkBiometricAvailability(): BiometricStatus {
        val state = DeviceScreenLock.read(context)
        val status = if (state == DeviceScreenLockState.SECURE_UNLOCKED || state == DeviceScreenLockState.SECURE_LOCKED) BiometricStatus.AVAILABLE else BiometricStatus.NONE_ENROLLED
        _biometricStatus.value = status
        return status
    }

    @Suppress("DEPRECATION")
    fun deviceCredentialIntent(): Intent? {
        authenticatedAtElapsed = null; authenticationAttempt.incrementAndGet()
        if (DeviceScreenLock.read(context) != DeviceScreenLockState.SECURE_UNLOCKED) return null
        return (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.createConfirmDeviceCredentialIntent("Unlock Private AI", "Use this phone's screen lock to unlock protected data.")
    }

    @Synchronized fun recordDeviceAuthentication() {
        // Retained with MainViewModel across rotation/foreground deferral. A
        // duplicate callback cannot extend the grant observed for this attempt.
        if (authenticatedAtElapsed == null) authenticatedAtElapsed = SystemClock.elapsedRealtime()
    }

    suspend fun completeDeviceCredentialAuthentication(requestedSessionMinutes: Int? = null,
        beforeGrant: () -> Boolean = { false }): Boolean {
        val proof = synchronized(this) { authenticatedAtElapsed?.let { it to authenticationAttempt.get() } } ?: return false
        if (EncryptionManager.hasCurrentAccess()) return false
        val epoch = EncryptionManager.accessGeneration
        return try {
            EncryptionManager.awaitPolicyIdle()
            currentCoroutineContext().ensureActive()
            if (EncryptionManager.accessGeneration != epoch) return false
            EncryptionManager.prepareOriginalKeyCreation()
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                if (authenticatedAtElapsed != proof.first || authenticationAttempt.get() != proof.second ||
                    EncryptionManager.accessGeneration != epoch || !beforeGrant()) return@withContext false
                EncryptionManager.unlockAfterDeviceAuthentication(proof.first, requestedSessionMinutes)
                true
            }
        } catch (failure: Exception) {
            if (authenticatedAtElapsed == proof.first && authenticationAttempt.get() == proof.second &&
                EncryptionManager.accessGeneration == epoch) lock()
            if (failure is CancellationException) throw failure
            false
        } finally { EncryptionManager.discardOriginalKeyPreparation(epoch) }
    }

    // Recreate early in onCreate so AndroidX rebinds the retained prompt's callback
    // after rotation. Report OS success first; the Activity checks foreground state
    // before actually granting application access through the Keystore check.
    fun createBiometricPrompt(activity: FragmentActivity, onAuthenticated: () -> Unit,
        onError: (String) -> Unit, onFailed: (String) -> Unit): BiometricPrompt {
        val registration = callbackRegistration.incrementAndGet()
        val owner = AtomicLong(if (pendingPromptAttempt.get() == authenticationAttempt.get()) authenticationAttempt.get() else -1)
        fun current(): Boolean = registration == callbackRegistration.get() && owner.get() >= 0 && owner.get() == authenticationAttempt.get()
        fun finish(): Boolean {
            if (!current()) return false
            val attempt = owner.getAndSet(-1)
            pendingPromptAttempt.compareAndSet(attempt, -1)
            return true
        }
        return BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (finish()) onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Only the current Activity/attempt may report a terminal event.
                    // The UI owns revocation after its retained ticket check.
                    if (finish()) onError("Device authentication did not complete.")
                }
                override fun onAuthenticationFailed() { if (current()) onFailed("Device authentication was not verified.") }
            }).also { synchronized(this) { promptOwners[it] = owner } }
    }

    fun promptBiometricAuth(prompt: BiometricPrompt, title: String = "Unlock Private AI",
        subtitle: String = "Authenticate with the device screen lock or biometrics") {
        check(DeviceScreenLock.read(context) == DeviceScreenLockState.SECURE_UNLOCKED)
        synchronized(this) {
            check(usedPrompts[prompt] != true) { "Create a fresh authentication prompt for each retry." }
            val owner = promptOwners[prompt] ?: error("Authentication prompt belongs to another manager.")
            authenticatedAtElapsed = null
            val attempt = authenticationAttempt.incrementAndGet()
            owner.set(attempt); pendingPromptAttempt.set(attempt); usedPrompts[prompt] = true
        }
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build())
    }

    // Application-entered PINs are not Android screen-lock authentication.
    @Suppress("UNUSED_PARAMETER")
    fun unlockWithPin(pin: String): Boolean = false

    fun lock() { EncryptionManager.lock() }
}
