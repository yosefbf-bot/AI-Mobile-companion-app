package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AutoDraftPreferences
import com.example.ui.hasLocalDraftWork
import com.example.ui.components.acknowledgeFormFields
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDraftUiBehaviorTest {
    @Test fun autoSaveDefaultsOnAndOffChoicePersistsWithoutErasingOtherPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getSharedPreferences("local_drafts", Context.MODE_PRIVATE)
        file.edit().clear().putString("unrelated", "retained").commit()
        assertTrue(AutoDraftPreferences(context).readEnabled())
        AutoDraftPreferences(context).saveEnabled(false)
        assertFalse(AutoDraftPreferences(context).readEnabled())
        assertEquals("retained", file.getString("unrelated", null))
        file.edit().clear().commit()
    }

    @Test fun defaultEmptyControlsAreNotPresentedAsUnfinishedWork() {
        assertFalse(hasLocalDraftWork("{}"))
        assertFalse(hasLocalDraftWork("{\"kind\":\"note\",\"tags\":\"default\",\"text\":\"\",\"evidence\":null}"))
        assertTrue(hasLocalDraftWork("{\"note\":\"Synthetic unsent note\"}"))
        assertTrue(hasLocalDraftWork("{\"evidence\":{\"name\":\"synthetic.txt\"}}"))
    }

    @Test fun successfulSaveAcknowledgesOnlyUnchangedSubmittedFields() {
        val submitted = "{\"note\":\"original\",\"owner\":\"same\",\"evidence\":{\"name\":\"a.txt\"}}"
        val current = "{\"note\":\"newer edit\",\"owner\":\"same\",\"evidence\":{\"name\":\"b.txt\"}}"
        val result = JSONObject(acknowledgeFormFields(current, submitted, setOf("note", "owner", "evidence")))
        assertEquals("newer edit", result.getString("note"))
        assertEquals("", result.getString("owner"))
        assertEquals("b.txt", result.getJSONObject("evidence").getString("name"))
        val identical = JSONObject(acknowledgeFormFields(submitted, submitted, setOf("note", "owner", "evidence")))
        assertEquals("", identical.getString("note"))
        assertTrue(identical.isNull("evidence"))
    }
}
