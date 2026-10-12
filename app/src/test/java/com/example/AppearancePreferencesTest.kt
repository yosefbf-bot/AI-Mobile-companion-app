package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.Appearance
import com.example.ui.theme.AppearancePreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePreferencesTest {
    @Test fun `appearance survives a new preferences instance without unlocking the vault`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(Appearance.DARK, AppearancePreferences(context).read())
        Appearance.entries.forEach { choice ->
            AppearancePreferences(context).save(choice)
            assertEquals(choice, AppearancePreferences(context).read())
        }
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("mode", "invalid").commit()
        assertEquals(Appearance.DARK, AppearancePreferences(context).read())
        assertTrue(Appearance.SYSTEM.isDark(true))
        assertFalse(Appearance.SYSTEM.isDark(false))
        assertTrue(Appearance.DARK.isDark(false))
        assertFalse(Appearance.LIGHT.isDark(true))
    }
}
