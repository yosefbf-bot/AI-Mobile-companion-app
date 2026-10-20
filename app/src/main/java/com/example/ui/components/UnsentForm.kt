package com.example.ui.components

import com.example.data.model.ChatAttachment
import org.json.JSONObject

/** Encrypted local draft serialization; structured edits avoid reserializing unchanged evidence. */
internal fun unsentForm(value: String): JSONObject = try { JSONObject(value) } catch (_: Exception) { JSONObject() }
internal fun attachmentForm(file: ChatAttachment?): Any = file?.let {
    JSONObject().put("name",it.name).put("type",it.type).put("data",it.base64Data).put("bytes",it.sizeBytes)
} ?: JSONObject.NULL
internal fun formAttachment(value: JSONObject): ChatAttachment? = value.optJSONObject("evidence")?.let {
    ChatAttachment(it.getString("name"),it.getString("type"),it.getString("data"),it.getLong("bytes"))
}

/** Acknowledge only unchanged committed fields; core durable receipts also reconcile after lock. */
internal fun acknowledgeChatDraft(current: String, submitted: String): String {
    val before = unsentForm(submitted)
    if (!before.has("text") || !before.has("evidence")) return current
    val now = unsentForm(current)
    if (now.optString("text") == before.optString("text")) now.put("text", "")
    if (now.opt("evidence")?.toString() == before.opt("evidence")?.toString()) now.put("evidence", JSONObject.NULL)
    return now.toString()
}

/** Clear only fields that still equal the submitted form; newer edits remain. */
internal fun acknowledgeFormFields(current: String, submitted: String, fields: Set<String>): String {
    val now = unsentForm(current)
    val before = unsentForm(submitted)
    fields.forEach { field ->
        if (before.has(field) && now.opt(field)?.toString() == before.opt(field)?.toString())
            now.put(field, if (field == "evidence") JSONObject.NULL else if (before.opt(field) is Boolean) false else "")
    }
    return now.toString()
}
