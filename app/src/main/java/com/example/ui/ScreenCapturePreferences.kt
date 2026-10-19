package com.example.ui

import android.content.Context

/** Nonsecret local display choice only; never stores vault content or authorization. */
class ScreenCapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("screen_capture", Context.MODE_PRIVATE)
    fun readAllowed(): Boolean = try {
        preferences.getBoolean("allow_screenshots", false)
    } catch (_: ClassCastException) { false }
    fun saveAllowed(allowed: Boolean) { preferences.edit().putBoolean("allow_screenshots", allowed).apply() }
}
