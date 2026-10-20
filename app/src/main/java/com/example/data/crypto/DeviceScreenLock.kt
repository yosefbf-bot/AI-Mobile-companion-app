package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context

enum class DeviceScreenLockState { SECURE_UNLOCKED, SECURE_LOCKED, NOT_CONFIGURED, UNAVAILABLE }

/** Android's secure credential includes PIN/password/pattern; Swipe/None never qualifies. */
object DeviceScreenLock {
    fun read(context: Context?): DeviceScreenLockState = try {
        val guard = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        when {
            guard == null -> DeviceScreenLockState.UNAVAILABLE
            !guard.isDeviceSecure -> DeviceScreenLockState.NOT_CONFIGURED
            guard.isDeviceLocked -> DeviceScreenLockState.SECURE_LOCKED
            else -> DeviceScreenLockState.SECURE_UNLOCKED
        }
    } catch (_: Exception) { DeviceScreenLockState.UNAVAILABLE }
}
