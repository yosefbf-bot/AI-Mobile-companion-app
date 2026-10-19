package com.example

import com.example.data.model.ChatAttachment
import com.example.ui.components.acknowledgeChatDraft
import com.example.ui.components.attachmentForm
import com.example.ui.components.formAttachment
import com.example.ui.components.unsentForm
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDraftAcknowledgmentTest {
    private val cipher = TestVaultCipher()
    private val evidence = ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9)
    private fun form(text: String, attachment: ChatAttachment?) = JSONObject().put("text", text)
        .put("evidence", attachmentForm(attachment)).toString()

    @Test fun durableSaveAcknowledgesEncryptedRamDraftBeforeComposerRecreation() {
        val submitted = form("  Exact submitted text  ", evidence)
        var encryptedRam = cipher.encrypt(submitted)
        // The same boundary used by MainViewModel, before any next Compose frame.
        encryptedRam = cipher.encrypt(acknowledgeChatDraft(cipher.decrypt(encryptedRam), submitted))
        val restored = unsentForm(cipher.decrypt(encryptedRam))
        assertEquals("", restored.optString("text"))
        assertNull(formAttachment(restored))
        assertFalse(encryptedRam.contains("Exact submitted text"))
        assertFalse(encryptedRam.contains(evidence.base64Data))
    }

    @Test fun acknowledgmentClearsOnlyIndividuallyUnchangedFieldsAcrossRecreation() {
        val submitted = form("Submitted", evidence)
        val newerEvidence = evidence.copy(name = "newer.txt", base64Data = "bmV3ZXI=", sizeBytes = 5)
        val newText = unsentForm(acknowledgeChatDraft(form("New edit", evidence), submitted))
        assertEquals("New edit", newText.optString("text"))
        assertNull(formAttachment(newText))
        val newFile = unsentForm(acknowledgeChatDraft(form("Submitted", newerEvidence), submitted))
        assertEquals("", newFile.optString("text"))
        assertEquals(newerEvidence, formAttachment(newFile))
        val both = form("New edit", newerEvidence)
        assertEquals(both, acknowledgeChatDraft(both, submitted))
    }

    @Test fun absentSubmissionSnapshotCannotEraseARetainedFailedDraft() {
        val current = form("Retained failed draft", evidence)
        assertEquals(current, acknowledgeChatDraft(current, ""))
        assertEquals(current, acknowledgeChatDraft(current, "invalid"))
    }
}
