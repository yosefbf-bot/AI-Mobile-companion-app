package com.example.ui.theme

import android.content.Context

// Display preference only: no vault data or credentials are stored here.
enum class Appearance(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark");
    fun isDark(systemDark: Boolean): Boolean = this == DARK || (this == SYSTEM && systemDark)
}
class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read(): Appearance = Appearance.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: Appearance.DARK
    fun save(value: Appearance) { preferences.edit().putString("mode", value.name).apply() }
}
