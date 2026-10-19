package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultCipher
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LocalDraft
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Opaque pre-encrypted intent. Carries no plaintext form or evidence. */
class LocalDraftSubmission internal constructor(internal val database: AppDatabase, internal val receipt: LocalDraft,
    internal val draftId: String, internal val deleteGeneration: Long)

internal object DraftAcknowledgments {
    fun hash(value: Any?): String = EncryptionManager.computeSha256(canonical(value))
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun prepare(database: AppDatabase, row: LocalDraft, submitted: String, clearFields: Set<String>, cipher: VaultCipher,
        durableCandidates: List<LocalDraft> = emptyList()): LocalDraftSubmission {
        require(clearFields.size in 1..64 && clearFields.all { it.length in 1..128 })
        val form = JSONObject(submitted); val hashes = JSONObject()
        for (field in clearFields) if (form.has(field)) hashes.put(field, hash(form.opt(field)))
        val candidates = JSONArray()
        for (candidate in durableCandidates.filter { it.id == row.id && it.deleteGeneration == row.deleteGeneration && !it.deleted }.distinctBy { it.revision }.take(4)) {
            val envelope = JSONObject(cipher.decrypt(candidate.encryptedFields ?: error(LocalDraftStore.FAILURE)))
            require(envelope.getInt("version") == 1 && envelope.getString("id") == row.id)
            val fields = envelope.getJSONObject("fields"); val known = JSONObject()
            for (field in clearFields) {
                if (field == "evidence") known.put(field, if (candidate.evidenceId == null) hash(JSONObject.NULL) else envelope.getString("evidenceDigest"))
                else if (fields.has(field)) known.put(field, hash(fields.opt(field)))
            }
            candidates.put(JSONObject().put("revision", candidate.revision).put("hashes", known))
        }
        val payload = JSONObject().put("version", 1).put("draftId", row.id).put("deleteGeneration", row.deleteGeneration)
            .put("hashes", hashes).put("durableCandidates", candidates)
        val receipt = LocalDraft("ack:" + UUID.randomUUID(), 0, true, cipher.encrypt(payload.toString()), null, System.currentTimeMillis())
        return LocalDraftSubmission(database, receipt, row.id, row.deleteGeneration)
    }
    /** Only already-encrypted values enter this bounded transaction, even after lock. */
    suspend fun commit(database: AppDatabase, submission: LocalDraftSubmission?, write: suspend () -> Unit) {
        if (submission == null) { write(); return }
        require(database === submission.database) { "The saved form belongs to another workspace." }
        withContext(NonCancellable) { database.withTransaction {
            val dao = database.localDraftDao()
            check(dao.acknowledgmentCount() < 1000) { LocalDraftStore.FAILURE }
            check(dao.bytes() + dao.evidenceBytes() + (submission.receipt.encryptedFields?.length ?: 0) <= LocalDraftStore.MAX_DISK_BYTES) { LocalDraftStore.FAILURE }
            write(); dao.put(submission.receipt)
        } }
    }
    fun intent(receipt: LocalDraft, cipher: VaultCipher): JSONObject {
        require(receipt.id.startsWith("ack:") && receipt.deleted && receipt.evidenceId == null)
        return JSONObject(cipher.decrypt(receipt.encryptedFields ?: error(LocalDraftStore.FAILURE))).also { require(it.getInt("version") == 1) }
    }
    fun apply(row: LocalDraft?, intent: JSONObject, cipher: VaultCipher, historical: Boolean = false): LocalDraft? {
        if (row == null || row.deleted || row.id != intent.getString("draftId") || row.deleteGeneration != intent.getLong("deleteGeneration")) return row
        val envelope = JSONObject(cipher.decrypt(row.encryptedFields ?: error(LocalDraftStore.FAILURE)))
        require(envelope.getInt("version") == 1 && envelope.getString("id") == row.id)
        val fields = envelope.getJSONObject("fields")
        val hashes = if (historical) intent.optJSONArray("durableCandidates")?.let { candidates ->
            (0 until candidates.length()).map { candidates.getJSONObject(it) }
                .firstOrNull { it.getLong("revision") == row.revision }?.getJSONObject("hashes")
        } ?: intent.getJSONObject("hashes") else intent.getJSONObject("hashes")
        var changed = false; var evidenceId = row.evidenceId
        for (key in hashes.keys()) {
            if (key == "evidence") {
                val currentHash = if (row.evidenceId == null) hash(JSONObject.NULL) else envelope.getString("evidenceDigest")
                if (currentHash == hashes.getString(key) && evidenceId != null) {
                    evidenceId = null; envelope.put("evidenceId", JSONObject.NULL).put("evidenceDigest", JSONObject.NULL); changed = true
                }
            } else if (fields.has(key) && hash(fields.opt(key)) == hashes.getString(key)) {
                val value = fields.opt(key)
                val replacement = if (value is String) "" else if (value is Boolean) false else JSONObject.NULL
                if (value != replacement) { fields.put(key, replacement); changed = true }
            }
        }
        check(row.revision < Long.MAX_VALUE && row.deleteGeneration < Long.MAX_VALUE)
        if (!changed) return row.copy(revision = row.revision + 1, deleteGeneration = row.deleteGeneration + 1, updatedAt = System.currentTimeMillis())
        val empty = evidenceId == null && fields.keys().asSequence().all { key ->
            val value = fields.opt(key); value == null || value == JSONObject.NULL || value == false || (value is String && value.isBlank()) }
        return row.copy(revision = row.revision + 1, deleteGeneration = row.deleteGeneration + 1, deleted = empty,
            encryptedFields = if (empty) null else cipher.encrypt(envelope.put("fields", fields).toString()), evidenceId = evidenceId,
            updatedAt = System.currentTimeMillis())
    }
}
