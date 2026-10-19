package com.example.ui.components

import com.example.data.model.ChatAttachment
import org.json.JSONObject

/** Callers store this serialization only in the ViewModel's encrypted RAM draft cache. */
internal fun unsentForm(value: String): JSONObject = try { JSONObject(value) } catch (_: Exception) { JSONObject() }
internal fun attachmentForm(file: ChatAttachment?): Any = file?.let {
    JSONObject().put("name",it.name).put("type",it.type).put("data",it.base64Data).put("bytes",it.sizeBytes)
} ?: JSONObject.NULL
internal fun formAttachment(value: JSONObject): ChatAttachment? = value.optJSONObject("evidence")?.let {
    ChatAttachment(it.getString("name"),it.getString("type"),it.getString("data"),it.getLong("bytes"))
}

/** Acknowledge only the fields actually committed; callers retain the result encrypted in RAM. */
internal fun acknowledgeChatDraft(current: String, submitted: String): String {
    val before = unsentForm(submitted)
    if (!before.has("text") || !before.has("evidence")) return current
    val now = unsentForm(current)
    if (now.optString("text") == before.optString("text")) now.put("text", "")
    if (now.opt("evidence")?.toString() == before.opt("evidence")?.toString()) now.put("evidence", JSONObject.NULL)
    return now.toString()
}
