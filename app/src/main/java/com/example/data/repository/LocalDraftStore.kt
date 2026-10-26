package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.VaultCipher
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LocalDraft
import com.example.data.local.entity.LocalDraftEvidence
import com.example.data.model.ChatAttachment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

data class LocalDraftToken internal constructor(internal val id: String, internal val revision: Long,
    internal val deletionGeneration: Long, internal val workspace: Long)
data class LocalDraftSnapshot(val token: LocalDraftToken, val form: String, val persisted: Boolean,
    val error: String? = null, val hasSavedDraft: Boolean = false)
data class LocalDraftStatus(val persisted: Boolean, val error: String? = null, val hasSavedDraft: Boolean = false)
internal data class DraftScope(val database: AppDatabase, val id: String, val workspace: Long)

/** Dedicated encrypted local forms. No row is exposed to network, library, outbox or export. */
internal class LocalDraftStore(private val cipher: VaultCipher,
    private val scopeFor: (String) -> DraftScope, private val checkScope: (LocalDraftToken) -> Unit,
    private val writeDelayMillis: Long = 200) {
    private class Entry(val scope: DraftScope, var ram: LocalDraft, var disk: LocalDraft?, var deletion: Long = ram.deleteGeneration,
        var error: String? = null, var evidence: WeakReference<ChatAttachment>? = null)
    private data class Write(val database: AppDatabase, val row: LocalDraft, val evidence: LocalDraftEvidence?)
    private data class Encoded(val row: LocalDraft, val evidence: LocalDraftEvidence?)
    private val guard = Any()
    private var locked = false
    private val entries = linkedMapOf<String, Entry>()
    private val evidenceCache = linkedMapOf<String, LocalDraftEvidence>()
    private val pending = linkedMapOf<String, Write>()
    // Includes in-flight writes until their committed row is installed: acknowledgments
    // must reconcile the highest admitted durable form, even before its first commit.
    private val admitted = linkedMapOf<String, Write>()
    private val inFlight = linkedMapOf<String, Write>()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val draining = Mutex()
    private val changes = MutableStateFlow(0L)
    val writeVersion: StateFlow<Long> = changes.asStateFlow()
    init { writer.launch { for (ignored in wake) { delay(writeDelayMillis); drain() } } }

    suspend fun load(route: String): LocalDraftSnapshot {
        val scope = scopeFor(route)
        synchronized(guard) { checkScope(LocalDraftToken(scope.id, 0, 0, scope.workspace)); locked = false }
        if (synchronized(guard) { entries[scope.id] } == null) {
            val (row, evidence) = withContext(Dispatchers.IO) { scope.database.withTransaction {
                val dao = scope.database.localDraftDao()
                val row = dao.byId(scope.id)
                row to row?.evidenceId?.let { dao.readEvidence(it) ?: error(FAILURE) }
            } }
            synchronized(guard) {
                checkScope(LocalDraftToken(scope.id, row?.revision ?: 0, 0, scope.workspace))
                if (!entries.containsKey(scope.id)) {
                    check(entries.size < MAX_ROWS) { FAILURE }
                    check(ramBytes() + (row?.let(::bytes) ?: 0) + (evidence?.encryptedPayload?.length ?: 0) <= MAX_RAM_BYTES) { FAILURE }
                    evidence?.let { verifyEvidence(it); evidenceCache[it.id] = it }
                    entries[scope.id] = Entry(scope, row ?: LocalDraft(scope.id, 0, true, null, null, 0), row)
                }
            }
        }
        return synchronized(guard) { snapshot(entries.getValue(scope.id), scope.workspace) }
    }
    private fun token(entry: Entry, workspace: Long) = LocalDraftToken(entry.ram.id, entry.ram.revision, entry.deletion, workspace)
    private fun entry(token: LocalDraftToken): Entry {
        checkScope(token)
        return entries[token.id]?.also { check(it.ram.revision == token.revision && it.deletion == token.deletionGeneration) {
            "The draft changed. Reload it before editing." } } ?: error("Load this draft before editing it.")
    }
    private fun state(entry: Entry) = LocalDraftStatus(entry.disk == entry.ram || (entry.disk == null && entry.ram.revision == 0L),
        entry.error, entry.disk?.deleted == false)
    private fun snapshot(entry: Entry, workspace: Long): LocalDraftSnapshot {
        val state = state(entry)
        return LocalDraftSnapshot(token(entry, workspace), decode(entry.ram), state.persisted, state.error, state.hasSavedDraft)
    }
    fun current(token: LocalDraftToken) = synchronized(guard) { snapshot(entry(token), token.workspace) }
    fun status(token: LocalDraftToken) = synchronized(guard) { state(entry(token)) }
    private fun split(form: String): Pair<String, ChatAttachment?> {
        val data = JSONObject(form)
        val evidence = data.optJSONObject("evidence")?.let {
            ChatAttachment(it.getString("name"), it.getString("type"), it.getString("data"), it.getLong("bytes")) }
        data.remove("evidence")
        return data.toString() to evidence
    }
    fun update(token: LocalDraftToken, form: String, autoSave: Boolean): LocalDraftToken {
        val (fields, evidence) = split(form)
        return updateFields(token, fields, evidence, autoSave)
    }
    fun updateFields(token: LocalDraftToken, fields: String, evidence: ChatAttachment?, autoSave: Boolean) = synchronized(guard) {
        val entry = entry(token)
        val encoded = encode(entry, fields, evidence)
        install(entry, encoded)
        entry.evidence = evidence?.let { WeakReference(it) }
        if (autoSave && entry.ram.revision != 0L) enqueue(entry, entry.ram)
        token(entry, token.workspace)
    }
    private fun encode(entry: Entry, fields: String, evidence: ChatAttachment?, reuseEvidenceId: String? = null): Encoded {
        require(fields.toByteArray(Charsets.UTF_8).size <= MAX_FIELDS_BYTES) { FAILURE }
        val data = JSONObject(fields); require(!data.has("evidence"))
        val empty = evidence == null && data.keys().asSequence().all { key ->
            val value = data.opt(key); value == null || value == JSONObject.NULL || value == false || (value is String && value.isBlank()) }
        if (empty && entry.ram.deleted) return Encoded(entry.ram, null)
        check(entry.ram.revision < Long.MAX_VALUE) { FAILURE }
        if (empty) return Encoded(LocalDraft(entry.ram.id, entry.ram.revision + 1, true, null, null, System.currentTimeMillis(), entry.ram.deleteGeneration), null)
        var candidateEvidenceDigest: String? = null
        val blob = when {
            evidence == null -> null
            reuseEvidenceId != null -> evidenceCache.getValue(reuseEvidenceId)
            entry.evidence?.get() === evidence && entry.ram.evidenceId != null -> evidenceCache.getValue(entry.ram.evidenceId!!)
            else -> {
                require(evidence.sizeBytes in 0..5L * 1024 * 1024 && evidence.base64Data.length <= 7 * 1024 * 1024) { FAILURE }
                val form = JSONObject().put("name", evidence.name).put("type", evidence.type)
                    .put("data", evidence.base64Data).put("bytes", evidence.sizeBytes)
                val digest = DraftAcknowledgments.hash(form)
                candidateEvidenceDigest = digest
                val previousDigest = entry.ram.encryptedFields?.let { JSONObject(cipher.decrypt(it)).optString("evidenceDigest", "") }
                if (entry.ram.evidenceId != null && previousDigest == digest) evidenceCache.getValue(entry.ram.evidenceId!!)
                else {
                    val id = UUID.randomUUID().toString()
                    val encrypted = cipher.encrypt(form.put("version", 1).put("draftId", entry.ram.id).put("evidenceId", id).toString())
                    LocalDraftEvidence(id, encrypted, EncryptionManager.computeSha256(encrypted))
                }
            }
        }
        val evidenceDigest = candidateEvidenceDigest ?: blob?.let {
            val old = if (entry.ram.evidenceId == it.id && entry.ram.encryptedFields != null)
                JSONObject(cipher.decrypt(entry.ram.encryptedFields!!)).optString("evidenceDigest", "") else ""
            old.ifBlank {
                val value = JSONObject(cipher.decrypt(it.encryptedPayload))
                value.remove("version"); value.remove("draftId"); value.remove("evidenceId")
                DraftAcknowledgments.hash(value)
            }
        }
        val encryptedFields = cipher.encrypt(JSONObject().put("version", 1).put("id", entry.ram.id).put("fields", data)
            .put("evidenceId", blob?.id ?: JSONObject.NULL).put("evidenceDigest", evidenceDigest ?: JSONObject.NULL).toString())
        return Encoded(LocalDraft(entry.ram.id, entry.ram.revision + 1, false, encryptedFields, blob?.id, System.currentTimeMillis(), entry.ram.deleteGeneration), blob)
    }
    private fun install(entry: Entry, encoded: Encoded, durable: Encoded? = null) {
        val references = (entries.values.flatMap { listOf(
            (if (it === entry) encoded.row else it.ram).evidenceId, it.disk?.evidenceId) } +
            pending.values.map { it.row.evidenceId } + admitted.values.map { it.row.evidenceId } +
            inFlight.values.map { it.row.evidenceId } + listOf(durable?.row?.evidenceId)).filterNotNull().toSet()
        val blobs = evidenceCache.toMutableMap()
        encoded.evidence?.let { blobs[it.id] = it }; durable?.evidence?.let { blobs[it.id] = it }
        val metadataBytes = entries.values.sumOf {
            val row = if (it === entry) encoded.row else it.ram
            bytes(row) + (if (it.disk != row) it.disk?.let(::bytes) ?: 0 else 0)
        }
        require(metadataBytes + blobs.filterKeys { it in references }.values.sumOf { it.encryptedPayload.length.toLong() } <= MAX_RAM_BYTES) { FAILURE }
        evidenceCache.clear(); evidenceCache.putAll(blobs.filterKeys { it in references })
        entry.ram = encoded.row; entry.error = null
    }
    private fun verifyEvidence(evidence: LocalDraftEvidence) {
        require(EncryptionManager.computeSha256(evidence.encryptedPayload) == evidence.ciphertextSha256) { FAILURE }
    }
    private fun decode(row: LocalDraft): String {
        if (row.deleted) return "{}"
        val envelope = JSONObject(cipher.decrypt(row.encryptedFields ?: error(FAILURE)))
        require(envelope.getInt("version") == 1 && envelope.getString("id") == row.id)
        val expectedEvidence = if (envelope.isNull("evidenceId")) null else envelope.getString("evidenceId")
        require(expectedEvidence == row.evidenceId)
        val form = envelope.getJSONObject("fields")
        form.put("evidence", row.evidenceId?.let { id ->
            val blob = evidenceCache[id] ?: error(FAILURE); verifyEvidence(blob)
            JSONObject(cipher.decrypt(blob.encryptedPayload)).also {
                require(it.getInt("version") == 1 && it.getString("draftId") == row.id && it.getString("evidenceId") == id)
                it.remove("version"); it.remove("draftId"); it.remove("evidenceId")
            }
        } ?: JSONObject.NULL)
        return form.toString()
    }
    private fun enqueue(entry: Entry, row: LocalDraft) {
        val write = Write(entry.scope.database, row, row.evidenceId?.let { evidenceCache.getValue(it) })
        pending[row.id] = write; admitted[row.id] = write; wake.trySend(Unit)
    }
    suspend fun delete(token: LocalDraftToken): LocalDraftSnapshot {
        val successor = synchronized(guard) {
            val entry = entry(token); check(entry.ram.revision < Long.MAX_VALUE)
            entry.ram = LocalDraft(token.id, entry.ram.revision + 1, true, null, null, System.currentTimeMillis(), entry.ram.deleteGeneration + 1)
            entry.deletion++; entry.evidence = null; entry.error = null; enqueue(entry, entry.ram)
            token(entry, token.workspace)
        }
        flush()
        return current(successor)
    }
    fun acknowledge(token: LocalDraftToken, submitted: String, reconcile: (String, String) -> String,
        submission: LocalDraftSubmission? = null) = synchronized(guard) {
        val entry = entry(token)
        if (submission != null && (submission.database !== entry.scope.database || submission.draftId != entry.ram.id ||
                submission.deleteGeneration != entry.ram.deleteGeneration)) return@synchronized token
        check(entry.ram.deleteGeneration < Long.MAX_VALUE && entry.ram.revision < Long.MAX_VALUE)
        fun reconcileRow(row: LocalDraft, historical: Boolean = false): Pair<String, Encoded> {
            if (historical && submission != null) {
                // The disk form may be an earlier autosaved partial while the
                // submitted complete form existed only in RAM with autosave off.
                val reconciled = DraftAcknowledgments.apply(row,
                    DraftAcknowledgments.intent(submission.receipt, cipher), cipher, historical = true)!!
                return decode(reconciled) to Encoded(reconciled,
                    reconciled.evidenceId?.let { evidenceCache.getValue(it) })
            }
            val original = decode(row)
            val remaining = reconcile(original, submitted)
            val (fields, evidence) = split(remaining)
            val reuse = if (JSONObject(original).opt("evidence")?.toString() == JSONObject(remaining).opt("evidence")?.toString()) row.evidenceId else null
            return remaining to encode(entry, fields, evidence, reuse)
        }
        val (remaining, encoded) = reconcileRow(entry.ram)
        val next = encoded.copy(row = encoded.row.copy(revision = maxOf(encoded.row.revision, entry.ram.revision + 1),
            deleteGeneration = entry.ram.deleteGeneration + 1))
        val durable = admitted[token.id]?.row ?: entry.disk
        val durableNext = if (durable == null || durable.deleted) null else {
            val (diskRemaining, diskEncoded) = reconcileRow(durable, historical = true)
            if (diskRemaining == remaining) next else diskEncoded.copy(row = diskEncoded.row.copy(revision = next.row.revision,
                deleteGeneration = next.row.deleteGeneration))
        }
        install(entry, next, durableNext)
        durableNext?.let { enqueue(entry, it.row) }
        entry.evidence = null; entry.deletion = entry.ram.deleteGeneration
        token(entry, token.workspace)
    }
    fun prepareSubmission(token: LocalDraftToken, submittedForm: String, clearFields: Set<String>): LocalDraftSubmission = synchronized(guard) {
        val entry = entry(token)
        val candidates = listOfNotNull(entry.disk, inFlight[token.id]?.row, pending[token.id]?.row, admitted[token.id]?.row)
        DraftAcknowledgments.prepare(entry.scope.database, entry.ram, submittedForm, clearFields, cipher, candidates)
    }
    /** Internal opening stage, after writer quiescence/policy conversion and before UI readiness. */
    suspend fun reconcileAcknowledgments(databases: List<AppDatabase>) {
        for (database in databases.distinct()) {
            for (receipt in database.localDraftDao().acknowledgments()) {
                val intent = DraftAcknowledgments.intent(receipt, cipher)
                val id = intent.getString("draftId")
                val cached = synchronized(guard) { entries[id]?.takeIf { it.scope.database === database }?.let { it.ram to (it.disk == it.ram) } }
                val ram = cached?.first?.let { DraftAcknowledgments.apply(it, intent, cipher) }
                val old = database.localDraftDao().byId(id)
                val prepared = DraftAcknowledgments.apply(old, intent, cipher, historical = true)
                withContext(NonCancellable) {
                    val committed = database.withTransaction {
                        val dao = database.localDraftDao()
                        check(dao.byId(id) == old) { "Draft changed during recovery; retry after unlocking." }
                        if (prepared != null && prepared != old) dao.put(prepared)
                        dao.deleteAcknowledgment(receipt.id)
                        prepared
                    }
                    synchronized(guard) { entries[id]?.takeIf { it.scope.database === database }?.let {
                        check(cached == null || it.ram == cached.first)
                        it.disk = committed
                        if (cached?.second == true && committed != null) it.ram = committed
                        else if (ram != null) it.ram = ram.copy(revision = maxOf(ram.revision, committed?.revision ?: 0))
                        it.deletion = it.ram.deleteGeneration; it.evidence = null
                    }; changes.value++ }
                }
            }
        }
    }
    private suspend fun drain() = draining.withLock {
        while (true) {
            val write = synchronized(guard) { pending.entries.firstOrNull()?.let {
                pending.remove(it.key)?.also { value -> inFlight[it.key] = value }
            } } ?: break
            try {
                val committed = withContext(NonCancellable) { write.database.withTransaction {
                    val dao = write.database.localDraftDao(); val old = dao.byId(write.row.id)
                    if (old == null || old.revision < write.row.revision) {
                        require(old != null || dao.count() < MAX_ROWS) { FAILURE }
                        val newEvidenceBytes = write.evidence?.takeIf { dao.evidenceHash(it.id) == null }?.encryptedPayload?.length ?: 0
                        require(dao.bytes() + dao.evidenceBytes() - (old?.let(::bytes) ?: 0) + bytes(write.row) + newEvidenceBytes <= MAX_DISK_BYTES) { FAILURE }
                        write.evidence?.let { blob ->
                            verifyEvidence(blob)
                            val existingHash = dao.evidenceHash(blob.id)
                            if (existingHash == null) dao.insertEvidence(blob) else check(existingHash == blob.ciphertextSha256) { FAILURE }
                        }
                        dao.put(write.row)
                    }
                    val actual = dao.byId(write.row.id)
                    check(actual == write.row) { "Draft changed in another window. Your current form was kept." }
                    write.evidence?.let { check(dao.evidenceHash(it.id) == it.ciphertextSha256) { FAILURE } }
                    actual!!
                } }
                synchronized(guard) {
                    entries[write.row.id]?.let { if ((it.disk?.revision ?: -1) <= committed.revision) it.disk = committed; it.error = null }
                    if (admitted[write.row.id]?.row == write.row) admitted.remove(write.row.id)
                    if (inFlight[write.row.id]?.row == write.row) inFlight.remove(write.row.id)
                    if (locked) trimDurable()
                    changes.value++
                }
                prune(write.database)
            } catch (failure: Exception) {
                synchronized(guard) {
                    if ((pending[write.row.id]?.row?.revision ?: -1) < write.row.revision) pending[write.row.id] = write
                    if (inFlight[write.row.id]?.row == write.row) inFlight.remove(write.row.id)
                    entries[write.row.id]?.error = FAILURE; changes.value++
                }
                if (failure is CancellationException) throw failure
                break
            }
        }
    }
    private fun referencedEvidence(): Set<String> = (entries.values.flatMap { listOf(it.ram.evidenceId, it.disk?.evidenceId) } +
        pending.values.map { it.row.evidenceId } + admitted.values.map { it.row.evidenceId } + inFlight.values.map { it.row.evidenceId }).filterNotNull().toSet()
    private suspend fun prune(database: AppDatabase) {
        val protected = synchronized(guard) { referencedEvidence() }
        database.withTransaction { database.localDraftDao().let { dao ->
            for (id in dao.orphanEvidence()) if (id !in protected) dao.deleteUnreferencedEvidence(id)
        } }
        synchronized(guard) { val needed = referencedEvidence(); evidenceCache.keys.retainAll(needed) }
    }
    suspend fun flush() { drain(); synchronized(guard) { check(pending.isEmpty() && admitted.isEmpty() && inFlight.isEmpty()) { FAILURE } } }
    private fun trimDurable() {
        entries.entries.removeAll { (id, entry) ->
            entry.error == null && (entry.ram == entry.disk || entry.disk == null && entry.ram.revision == 0L) &&
                id !in pending && id !in admitted && id !in inFlight
        }
        evidenceCache.keys.retainAll(referencedEvidence())
    }
    internal fun retainedBytes(): Long = synchronized(guard) { ramBytes() }
    fun onLocked() { synchronized(guard) { locked = true; entries.values.forEach { it.evidence = null }; trimDurable(); wake.trySend(Unit) } }
    suspend fun rewrapMemory(rewrite: (String) -> String, checkAccess: () -> Unit = {}) {
        val snapshot = synchronized(guard) {
            checkAccess(); check(pending.isEmpty() && admitted.isEmpty() && inFlight.isEmpty()) { FAILURE }
            entries.values.map { Triple(it, it.ram, it.disk) } to evidenceCache.toMap()
        }
        val convertedRows = snapshot.first.associate { (entry, ram, disk) ->
            fun row(value: LocalDraft) = value.copy(encryptedFields = value.encryptedFields?.let(rewrite))
            val converted = row(ram)
            entry to (converted to if (disk == ram) converted else disk?.let(::row))
        }
        val blobs = linkedMapOf<String, LocalDraftEvidence>()
        for ((id, oldBlob) in snapshot.second) {
            checkAccess()
            val owners = snapshot.first.filter { (_, ram, disk) -> ram.evidenceId == id || disk?.evidenceId == id }
            if (owners.isEmpty()) continue
            val database = owners.first().first.scope.database
            val physical = withContext(Dispatchers.IO) { database.withTransaction { database.localDraftDao().readEvidence(id) } }
            checkAccess()
            val replacement = if (physical != null) {
                verifyEvidence(physical)
                check(rewrite(physical.encryptedPayload) == physical.encryptedPayload) { "Persisted evidence policy was not converted." }
                physical
            } else {
                // A missing physically referenced blob is corruption; RAM-only OFF
                // evidence can legitimately have no database row yet.
                for ((entry, _, disk) in owners) if (disk?.evidenceId == id) {
                    val current = database.localDraftDao().byId(entry.ram.id)
                    check(current?.evidenceId != id) { "Persisted draft evidence is unavailable." }
                }
                rewrite(oldBlob.encryptedPayload).let { oldBlob.copy(encryptedPayload = it, ciphertextSha256 = EncryptionManager.computeSha256(it)) }
            }
            blobs[id] = replacement
        }
        synchronized(guard) {
            fun adopt() {
                checkAccess()
                check(pending.isEmpty() && admitted.isEmpty() && inFlight.isEmpty())
                snapshot.first.forEach { (entry, oldRam, oldDisk) ->
                    check(entry.ram == oldRam && entry.disk == oldDisk)
                    val converted = convertedRows.getValue(entry)
                    entry.ram = converted.first; entry.disk = converted.second; entry.evidence = null
                }
                evidenceCache.clear(); evidenceCache.putAll(blobs)
            }
            if (cipher === EncryptionManager) synchronized(EncryptionManager) { adopt() } else adopt()
        }
    }
    fun close() { writer.launch { try { flush() } catch (_: Exception) { /* Error state already retains the unsaved form. */ } finally { writer.cancel() } } }
    private fun bytes(row: LocalDraft) = (row.encryptedFields?.length ?: 0).toLong()
    private fun ramBytes() = entries.values.sumOf { bytes(it.ram) + (if (it.disk != it.ram) it.disk?.let(::bytes) ?: 0 else 0) } + evidenceCache.values.sumOf { it.encryptedPayload.length.toLong() }
    companion object {
        const val FAILURE = "Draft could not be saved. Your current form was kept; existing drafts were not deleted. Check local draft storage and retry."
        const val MAX_ROWS = 1000L
        const val MAX_FIELDS_BYTES = 256 * 1024
        const val MAX_RAM_BYTES = 32L * 1024 * 1024
        const val MAX_DISK_BYTES = 64L * 1024 * 1024
    }
}
