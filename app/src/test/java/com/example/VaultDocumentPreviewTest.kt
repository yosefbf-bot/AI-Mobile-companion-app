package com.example

import android.util.Base64
import com.example.data.crypto.VaultCodec
import com.example.data.local.entity.EncryptedDocument
import com.example.ui.screens.vault.vaultDocumentPreview
import com.example.ui.screens.vault.vaultDocumentShareText
import com.example.ui.screens.vault.vaultDocumentChatPreview
import com.example.data.network.DesktopNodeService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VaultDocumentPreviewTest {
    @Test fun largePendingEvidenceDisplaysOnlyBoundedSummaryAndPreservesEncryptedSource() {
        val data = Base64.encodeToString(ByteArray(5 * 1024 * 1024) { 65 }, Base64.NO_WRAP)
        val source = JSONObject().put("sourceId","synthetic-source").put("text","SYNTHETIC observation for review")
            .put("kind","round").put("owner","").put("location","SYNTHETIC hallway").put("dueAt",JSONObject.NULL)
            .put("desktopFingerprint","private-device-binding-omitted")
            .put("localEvidence",JSONObject().put("name","synthetic-evidence.txt").put("type","DOCUMENT").put("data",data)).toString()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = VaultCodec.encrypt(source,key)
        val document = EncryptedDocument("synthetic-id","pending review","CAPTURE_PENDING",encrypted,"a".repeat(64),source.length.toLong())
        val preview = vaultDocumentPreview(document,VaultCodec.decrypt(document.encryptedContent,key))
        assertTrue(preview.length < 16000)
        assertTrue(preview.contains("SYNTHETIC observation for review"))
        assertTrue(preview.contains("synthetic-evidence.txt"))
        assertTrue(preview.contains("5242880"))
        assertTrue(preview.contains("Owner: Unknown"))
        assertTrue(preview.contains("Due: Unknown"))
        assertFalse(preview.contains(data.take(120)))
        assertFalse(preview.contains("private-device-binding-omitted"))
        assertFalse(preview.contains("localEvidence"))
        assertEquals(encrypted,document.encryptedContent)
        assertEquals(source,VaultCodec.decrypt(document.encryptedContent,key))
    }

    @Test fun desktopReceiptPreviewDoesNotExposeDraftEnvelopeAndNormalDocumentIsBounded() {
        val source = JSONObject().put("capture",JSONObject().put("id","synthetic-capture").put("sourceId","synthetic-source")
            .put("text","SYNTHETIC confirmed capture receipt").put("kind","note").put("evidenceIds",org.json.JSONArray().put("synthetic-evidence")))
            .put("drafts",org.json.JSONArray().put(JSONObject().put("body","synthetic-envelope-body-omit"))).toString()
        val record = EncryptedDocument("synthetic-id","saved review","CAPTURE_RECEIPT","retained-ciphertext","b".repeat(64),source.length.toLong())
        val preview = vaultDocumentPreview(record,source)
        assertTrue(preview.contains("Desktop receipt saved; human review still required"))
        assertTrue(preview.contains("synthetic-capture"))
        assertTrue(preview.contains("synthetic-evidence"))
        assertFalse(preview.contains("synthetic-envelope-body-omit"))
        assertEquals(preview,vaultDocumentShareText(record,source))
        val text = "SYNTHETIC manual note. ".repeat(2000)
        val limited = vaultDocumentPreview(record.copy(fileType="MARKDOWN"),text)
        assertTrue(limited.length < 16200)
        assertTrue(limited.contains("Original encrypted content is retained"))
        assertEquals(text,vaultDocumentShareText(record.copy(fileType="MARKDOWN"),text))
        assertEquals("retained-ciphertext",record.encryptedContent)
    }

    @Test fun chatPreviewFitsRealMessageAndEncodedJsonLimitsIncludingUnicodeAndControlEscapes() {
        val values = listOf("SYNTHETIC report section. ".repeat(1000), "SYNTHETIC \uD83D\uDE00三".repeat(2500), "SYNTHETIC\u0001".repeat(2500))
        values.forEach { original ->
            val preview = vaultDocumentChatPreview(original)
            assertTrue(preview.contains("Partial preview"))
            val json = DesktopNodeService.chatRequest("shared_" + "s".repeat(36),"Analyze this report:\n\n" + preview,"r".repeat(36),true,true)
            assertTrue(json.getString("prompt").length <= 12000)
            assertTrue(json.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            val beforeMarker = preview.substringBefore("\n\n[Partial preview")
            assertTrue(beforeMarker.length <= 10000)
            assertFalse(beforeMarker.lastOrNull()?.let(Character::isHighSurrogate) ?: false)
        }
        assertEquals("SYNTHETIC short note",vaultDocumentChatPreview("SYNTHETIC short note"))
    }
}
