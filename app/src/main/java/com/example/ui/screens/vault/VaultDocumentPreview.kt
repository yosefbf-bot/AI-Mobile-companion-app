package com.example.ui.screens.vault

import com.example.data.local.entity.EncryptedDocument
import com.example.data.network.DesktopNodeService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display/export a bounded summary; never lay out a transport envelope or encoded file. */
internal fun vaultDocumentPreview(document: EncryptedDocument, content: String): String {
    val limit = 16000
    if (!document.fileType.startsWith("CAPTURE_")) return boundedPreview(content, limit)
    val root = try { JSONObject(content) } catch (_: Exception) {
        return "Protected capture record could not be displayed. Its original encrypted content is retained."
    }
    val note = root.optJSONObject("capture") ?: root
    fun field(name: String, max: Int = 160) = note.optString(name).take(max).ifBlank { "Unknown" }
    val status = when (document.fileType) {
        "CAPTURE_RECEIPT" -> "Desktop receipt saved; human review still required."
        "CAPTURE_REVIEW_REQUIRED" -> "Saved on this phone; review required before resubmitting."
        else -> "Saved on this phone; pending desktop delivery."
    }
    val due = if (note.isNull("dueAt")) null else note.optLong("dueAt").takeIf { it > 0 && it <= 8640000000000000L }
    val sourceId = note.optString("sourceId").ifBlank { document.id }.take(100)
    val evidence = root.optJSONObject("localEvidence")
    val encoded = evidence?.optString("data").orEmpty()
    val estimatedBytes = if (encoded.isBlank()) null else encoded.length.toLong() * 3 / 4 - encoded.takeLast(2).count { it == '=' }
    val references = note.optJSONArray("evidenceIds")
    return boundedPreview(buildString {
        appendLine(status)
        appendLine("Source receipt: $sourceId")
        note.optString("id").takeIf(String::isNotBlank)?.let { appendLine("Desktop capture: ${it.take(100)}") }
        appendLine("Kind: ${field("kind")}")
        appendLine("Location: ${field("location")}")
        appendLine("Owner: ${field("owner",100)}")
        appendLine("Due: ${due?.let { SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT).format(Date(it)) } ?: "Unknown"}")
        appendLine()
        appendLine(note.optString("text").take(12000).ifBlank { "Observation text unavailable." })
        if (evidence != null) {
            appendLine()
            appendLine("Evidence file: ${evidence.optString("name").take(160).ifBlank { "Unknown" }}")
            appendLine("Evidence type: ${evidence.optString("type").take(30).ifBlank { "Unknown" }}")
            appendLine("Evidence bytes: ${estimatedBytes ?: "Unknown"} (encoded-size estimate)")
        }
        if (references != null && references.length() > 0) {
            appendLine()
            appendLine("Desktop evidence references: " + (0 until minOf(references.length(),6)).joinToString(", ") { references.optString(it).take(100) })
        }
        appendLine()
        appendLine("File contents and transport data are omitted. Photos were not interpreted by the text model.")
        appendLine("Stored record checksum: ${document.checksumSha256.take(64)}")
    }, limit)
}

/** Capture envelopes are internal transport records; ordinary user documents keep full export. */
internal fun vaultDocumentShareText(document: EncryptedDocument, content: String): String =
    if (document.fileType.startsWith("CAPTURE_")) vaultDocumentPreview(document, content) else content

/** Includes the activity's existing report preamble and worst-case session/request ID overhead. */
internal fun vaultDocumentChatPreview(content: String): String {
    val prefix = "Analyze this report:\n\n"
    val partial = "\n\n[Partial preview; the full protected original is retained.]"
    fun boundary(end: Int): Int = if (end > 0 && end < content.length &&
        Character.isHighSurrogate(content[end-1]) && Character.isLowSurrogate(content[end])) end-1 else end
    fun preview(end: Int) = content.take(boundary(end)) + if (boundary(end) < content.length) partial else ""
    fun fits(end: Int): Boolean = try {
        DesktopNodeService.chatRequest("s".repeat(80),prefix + preview(end),"r".repeat(80),false,false)
            .toString().toByteArray(Charsets.UTF_8).size <= 30 * 1024
    } catch (_: Exception) { false }
    var low = 0
    var high = minOf(content.length,10000)
    if (fits(high)) return preview(high)
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (fits(middle)) low = middle else high = middle-1
    }
    return preview(low)
}

private fun boundedPreview(content: String, limit: Int): String = if (content.length <= limit) content else
    content.take(limit) + "\n\n[Preview limited to $limit characters. Original encrypted content is retained.]"
