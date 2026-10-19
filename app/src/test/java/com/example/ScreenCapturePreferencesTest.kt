package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.ScreenCapturePreferences
import com.example.ui.theme.Appearance
import com.example.ui.theme.AppearancePreferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCapturePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun cleanFixture() { context.getSharedPreferences("screen_capture", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun clearFixture() { cleanFixture() }

    @Test fun newInstallationBlocksScreenCaptureWithoutVaultAccess() {
        assertFalse(ScreenCapturePreferences(context).readAllowed())
    }

    @Test fun explicitChoicePersistsAcrossInstancesIndependentlyOfAppearance() {
        AppearancePreferences(context).save(Appearance.LIGHT)
        ScreenCapturePreferences(context).saveAllowed(true)
        assertTrue(ScreenCapturePreferences(context).readAllowed())
        assertEquals(Appearance.LIGHT, AppearancePreferences(context).read())
        AppearancePreferences(context).save(Appearance.DARK)
        assertTrue(ScreenCapturePreferences(context).readAllowed())
        ScreenCapturePreferences(context).saveAllowed(false)
        assertFalse(ScreenCapturePreferences(context).readAllowed())
    }

    @Test fun malformedStoredChoiceFailsClosedInsteadOfEnablingCapture() {
        context.getSharedPreferences("screen_capture", Context.MODE_PRIVATE).edit().putString("allow_screenshots", "true").commit()
        assertFalse(ScreenCapturePreferences(context).readAllowed())
    }
}
