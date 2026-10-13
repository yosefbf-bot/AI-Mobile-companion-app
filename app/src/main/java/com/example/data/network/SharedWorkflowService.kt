package com.example.data.network

import android.util.Base64
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class SharedWorkflowService(private val transport: DesktopNodeService) {
    suspend fun conversations(config: DesktopNodeConfig): List<JSONObject> =
        objects(transport.request(config, "/mobile/api/v1/conversations").getJSONArray("conversations"))

    suspend fun history(config: DesktopNodeConfig, sessionId: String): JSONObject =
        transport.request(config, "/mobile/api/v1/conversations/history", JSONObject().put("sessionId", sessionId))

    suspend fun uploadEvidence(config: DesktopNodeConfig, sourceId: String, attachment: ChatAttachment): String {
        val lease = transport.authorizationLease(config)
        require(attachment.base64Data.length <= 7 * 1024 * 1024) { "Evidence is limited to 5 MiB." }
        val bytes = Base64.decode(attachment.base64Data, Base64.NO_WRAP)
        require(bytes.size in 1..5 * 1024 * 1024) { "Evidence is limited to 5 MiB." }
        val mime = when {
            bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) -> "image/png"
            bytes.size >= 2 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() -> "image/jpeg"
            bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
            attachment.type == "DOCUMENT" && attachment.name.endsWith(".txt", true) -> "text/plain"
            else -> throw GatewayException("UNSUPPORTED", message = "Evidence supports JPEG, PNG, PDF or plain text. Images are stored for review, not automatically interpreted.")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val uploadId = UUID.nameUUIDFromBytes("$sourceId|$hash".toByteArray()).toString()
        val chunkSize = 80 * 1024
        val total = (bytes.size + chunkSize - 1) / chunkSize
        var evidenceId: String? = null
        for (index in 0 until total) {
            val data = Base64.encodeToString(bytes.copyOfRange(index * chunkSize, minOf(bytes.size, (index + 1) * chunkSize)), Base64.NO_WRAP)
            val result = transport.request(config, "/mobile/api/v1/evidence/write", JSONObject()
                .put("uploadId", uploadId).put("index", index).put("total", total).put("name", attachment.name.take(160))
                .put("mime", mime).put("bytes", bytes.size).put("sha256", hash).put("data", data), lease)
            require(result.getString("uploadId") == uploadId && result.getInt("index") == index) { "Evidence acknowledgment did not match." }
            if (result.optBoolean("complete")) {
                require(result.getString("sha256") == hash && result.getInt("bytes") == bytes.size) { "Evidence integrity check failed." }
                evidenceId = result.getString("evidenceId")
                break // A completed upload replay can confirm every repeated chunk.
            }
        }
        return evidenceId ?: throw GatewayException("PROTOCOL", message = "Desktop did not confirm the complete evidence file.")
    }

    suspend fun createCapture(config: DesktopNodeConfig, input: JSONObject): JSONObject =
        transport.request(config, "/mobile/api/v1/captures/create", input)

    suspend fun snapshot(config: DesktopNodeConfig, scopes: Set<String>): WorkflowSnapshot {
        val lease = transport.authorizationLease(config)
        val data = if ("workflow" in scopes) transport.request(config, "/mobile/api/v1/actions", lease = lease) else JSONObject()
        val notices = if ("notifications" in scopes) transport.request(config, "/mobile/api/v1/notifications", lease = lease) else JSONObject()
        return WorkflowSnapshot(
            captures = objects(data.optJSONArray("captures")).map { c -> WorkflowCapture(c.getString("id"), c.optString("text"), c.optString("kind"), c.optString("location"), c.optLong("created"), strings(c.optJSONArray("evidenceIds"))) },
            actions = objects(data.optJSONArray("actions")).map(::action),
            drafts = objects(data.optJSONArray("drafts")).map(::draft),
            notifications = objects(notices.optJSONArray("notifications")).map { n -> FollowUpNotification(n.getString("id"), nullableString(n, "actionId"), n.optLong("created"), n.optString("status")) },
            truncated = data.optBoolean("truncated") || notices.optBoolean("truncated")
        )
    }

    suspend fun updateAction(config: DesktopNodeConfig, item: WorkflowAction, owner: String, dueAt: Long?, note: String, complete: Boolean): WorkflowAction {
        val result = transport.request(config, "/mobile/api/v1/actions/update", JSONObject().put("id", item.id).put("revision", item.revision)
            .put("owner", owner).put("dueAt", dueAt ?: JSONObject.NULL).put("note", note).put("complete", complete))
        return action(result.optJSONObject("action") ?: result)
    }

    suspend fun saveDraft(config: DesktopNodeConfig, item: WorkflowDraft, recipient: String, body: String, subject: String): WorkflowDraft {
        val result = transport.request(config, "/mobile/api/v1/drafts/save", JSONObject().put("id", item.id).put("revision", item.revision)
            .put("captureId", item.captureId).put("channel", item.channel).put("recipient", recipient).put("body", body).put("subject", subject))
        return draft(result.optJSONObject("draft") ?: result)
    }

    suspend fun acknowledgeNotification(config: DesktopNodeConfig, id: String) {
        val result = transport.request(config, "/mobile/api/v1/notifications/ack", JSONObject().put("id", id))
        require(result.getString("id") == id && result.getString("status") == "acknowledged") { "Notification acknowledgment was not confirmed." }
    }

    suspend fun meetings(config: DesktopNodeConfig): List<DesktopMeeting> =
        objects(transport.request(config, "/mobile/api/v1/meetings").getJSONArray("meetings")).map {
            DesktopMeeting(it.getString("sourceId"), it.getString("title"), it.getString("status"), it.optInt("pendingAudio"),
                it.optString("summary"), objects(it.optJSONArray("actions")).map { a -> a.optString("title") })
        }

    suspend fun deliverMeeting(config: DesktopNodeConfig, kind: String, input: JSONObject): String {
        if (kind != "MEETING_AUDIO") {
            val endpoint = when(kind) { "MEETING_CREATE" -> "create"; "MEETING_FINISH" -> "finish"; else -> error("Unsupported meeting operation.") }
            val result = transport.request(config, "/mobile/api/v1/meetings/$endpoint", input)
            require(result.getString("sourceId") == input.getString("sourceId")) { "Meeting acknowledgment did not match." }
            return result.getString("id")
        }
        val lease = transport.authorizationLease(config)
        val bytes = Base64.decode(input.getString("audio"), Base64.NO_WRAP)
        require(bytes.size in 364..640044)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val chunkId = input.getString("chunkId")
        val size = 80 * 1024
        val total = (bytes.size + size - 1) / size
        for (index in 0 until total) {
            val result = transport.request(config, "/mobile/api/v1/meetings/audio", JSONObject()
                .put("sourceId", input.getString("sourceId")).put("chunkId", chunkId).put("index", index).put("total", total)
                .put("bytes", bytes.size).put("sha256", hash)
                .put("data", Base64.encodeToString(bytes.copyOfRange(index * size, minOf(bytes.size, (index + 1) * size)), Base64.NO_WRAP)), lease)
            require(result.getString("chunkId") == chunkId && result.getInt("index") == index)
            if (result.optBoolean("complete")) {
                require(result.getString("sha256") == hash && result.getInt("bytes") == bytes.size) { "Recording integrity check failed." }
                return result.getString("audioId")
            }
        }
        throw GatewayException("PROTOCOL", message = "Desktop did not confirm the complete recording chunk.")
    }

    companion object {
        fun action(a: JSONObject) = WorkflowAction(a.getString("id"), a.optString("title"), a.optString("description"), a.optString("owner"),
            if (a.isNull("dueAt")) null else a.getLong("dueAt"), a.optString("status"), a.getString("revision"), nullableString(a, "captureId"),
            a.optBoolean("reviewRequired", true), strings(a.optJSONArray("unknownFields")))
        fun draft(d: JSONObject) = WorkflowDraft(d.getString("id"), nullableString(d, "captureId"), d.optString("channel"), d.optString("body"),
            d.optString("recipient"), d.optString("status", "draft"), d.optLong("revision"), d.optString("subject"))
        private fun nullableString(json: JSONObject, key: String) = if (json.isNull(key)) null else json.optString(key).takeIf(String::isNotBlank)
        private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
        private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    }
}
