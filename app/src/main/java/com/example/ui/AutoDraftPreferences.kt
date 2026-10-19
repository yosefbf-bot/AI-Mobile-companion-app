package com.example.ui

import android.content.Context

/** One nonsecret local preference; switching off never deletes saved work. */
class AutoDraftPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("local_drafts", Context.MODE_PRIVATE)
    fun readEnabled(): Boolean = preferences.getBoolean("auto_save", true)
    fun saveEnabled(enabled: Boolean) { check(preferences.edit().putBoolean("auto_save", enabled).commit()) }
}
