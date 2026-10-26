package com.example.data.repository

import android.util.Base64
import androidx.room.withTransaction
import com.example.data.crypto.VaultCipher
import com.example.data.local.AppDatabase
import com.example.data.local.entity.PendingOperation
import org.json.JSONObject
import java.util.UUID

data class PhoneMeeting(val sourceId: String, val title: String, val chunks: Int, val finishQueued: Boolean,
    val finishAcknowledged: Boolean = false, val reviewError: String? = null)

/** No plaintext audio files; each bounded recording is durable before transport. */
class MeetingOutbox(private val db: AppDatabase, private val cipher: VaultCipher) {
    private val dao get() = db.pendingOperationDao()
    private fun row(kind: String, binding: String, value: JSONObject, id: String = UUID.randomUUID().toString(), status: String = "PENDING") =
        PendingOperation(id, kind, cipher.encrypt(value.toString()), binding, System.currentTimeMillis(), status)
    suspend fun list(binding: String): List<PhoneMeeting> {
        val states = dao.meetingStates(binding).map { JSONObject(cipher.decrypt(it.encryptedPayload)) }
        // Older state rows have no receipt flag. Their existing encrypted finish receipts
        // remain valid proof; never infer submission from finishQueued alone.
        val oldReceipts = if (states.any { it.getBoolean("finishQueued") && !it.has("finishAcknowledged") })
            dao.acknowledgedMeetingFinishes(binding).mapNotNull {
                val receipt = JSONObject(cipher.decrypt(it.encryptedPayload))
                receipt.getString("sourceId").takeIf { _ -> receipt.getString("serverId") == it.serverId }
            }.toSet() else emptySet()
        return states.map { data ->
            PhoneMeeting(data.getString("sourceId"), data.getString("title"), data.getInt("chunks"), data.getBoolean("finishQueued"),
                data.optBoolean("finishAcknowledged") || data.getString("sourceId") in oldReceipts,
                data.optString("reviewError").takeIf { it.isNotBlank() })
        }
    }
    suspend fun create(binding: String, title: String): String = db.withTransaction {
        require(binding.isNotBlank())
        val id = UUID.randomUUID().toString()
        val data = JSONObject().put("sourceId", id).put("title", title.trim().take(150).ifBlank { "Meeting" })
        dao.enqueue(row("MEETING_CREATE", binding, data))
        dao.enqueue(row("MEETING_STATE", binding, JSONObject(data.toString()).put("chunks", 0).put("finishQueued", false).put("finishAcknowledged", false), id, "LOCAL"))
        id
    }
    private suspend fun state(binding: String, id: String): JSONObject {
        val row = dao.meetingState(id) ?: error("Recording not found.")
        require(row.desktopFingerprint == binding) { "Recording belongs to a different pairing." }
        return JSONObject(cipher.decrypt(row.encryptedPayload))
    }
    suspend fun append(binding: String, id: String, wav: ByteArray) = db.withTransaction {
        require(wav.size in 364..640044)
        val state = state(binding, id)
        check(!state.has("reviewError")) { "Review this meeting's delivery on the desktop before continuing." }
        check(!state.getBoolean("finishQueued") && state.getInt("chunks") < 180)
        check(dao.meetingBytes() + wav.size * 2 < 512L * 1024 * 1024) { "Pending recording storage is full. Sync with the desktop first." }
        dao.enqueue(row("MEETING_AUDIO", binding, JSONObject().put("sourceId", id).put("chunkId", UUID.randomUUID().toString())
            .put("audio", Base64.encodeToString(wav, Base64.NO_WRAP))))
        state.put("chunks", state.getInt("chunks") + 1)
        dao.updateMeetingState(id, cipher.encrypt(state.toString()))
    }
    suspend fun finish(binding: String, id: String) = db.withTransaction {
        val state = state(binding, id)
        check(!state.has("reviewError")) { "Review this meeting's delivery on the desktop before continuing." }
        if (!state.getBoolean("finishQueued")) {
            dao.enqueue(row("MEETING_FINISH", binding, JSONObject().put("sourceId", id).put("expectedChunkCount", state.getInt("chunks"))))
            dao.updateMeetingState(id, cipher.encrypt(state.put("finishQueued", true).toString()))
        }
    }

    suspend fun reviewError(binding: String, id: String): String? =
        state(binding, id).optString("reviewError").takeIf { it.isNotBlank() }

    /** Retain the failed operation's encrypted bytes and a durable meeting-level explanation. */
    suspend fun requireReview(operation: PendingOperation, sourceId: String, error: String) = db.withTransaction {
        val state = state(operation.desktopFingerprint, sourceId)
        state.put("reviewError", error).put("reviewOperation", operation.operation)
        dao.updateMeetingState(sourceId, cipher.encrypt(state.toString()))
        dao.reviewMeetingOperation(operation.id)
    }

    suspend fun acknowledge(operation: PendingOperation, sourceId: String, serverId: String) = db.withTransaction {
        dao.acknowledgeMeeting(operation.id, serverId,
            cipher.encrypt(JSONObject().put("sourceId", sourceId).put("serverId", serverId).toString()))
        if (operation.operation == "MEETING_FINISH") {
            val state = state(operation.desktopFingerprint, sourceId)
            dao.updateMeetingState(sourceId, cipher.encrypt(state.put("finishAcknowledged", true).toString()))
        }
    }
}
