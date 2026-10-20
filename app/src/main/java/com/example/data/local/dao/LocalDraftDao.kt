package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.LocalDraft
import com.example.data.local.entity.LocalDraftEvidence

@Dao
interface LocalDraftDao {
    @Query("SELECT * FROM local_drafts WHERE id = :id") suspend fun byId(id: String): LocalDraft?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: LocalDraft)
    @Query("SELECT * FROM local_drafts WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun afterForPolicy(after: String?, limit: Int): List<LocalDraft>
    @Query("SELECT COUNT(*) FROM local_drafts WHERE id NOT LIKE 'ack:%'") suspend fun count(): Long
    @Query("SELECT COALESCE(SUM(COALESCE(length(encryptedFields),0)),0) FROM local_drafts")
    suspend fun bytes(): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEvidence(row: LocalDraftEvidence)
    @Query("SELECT ciphertextSha256 FROM local_draft_evidence WHERE id = :id") suspend fun evidenceHash(id: String): String?
    @Query("SELECT length(encryptedPayload) FROM local_draft_evidence WHERE id = :id") suspend fun evidenceLength(id: String): Long?
    @Query("SELECT substr(encryptedPayload,:start,:count) FROM local_draft_evidence WHERE id = :id")
    suspend fun evidenceSlice(id: String, start: Int, count: Int): String?
    @Query("SELECT COALESCE(SUM(length(encryptedPayload)),0) FROM local_draft_evidence") suspend fun evidenceBytes(): Long
    @Query("SELECT id FROM local_draft_evidence WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun evidenceIdsAfter(after: String?, limit: Int): List<String>
    @Query("SELECT id FROM local_draft_evidence WHERE NOT EXISTS (SELECT 1 FROM local_drafts WHERE evidenceId = local_draft_evidence.id) LIMIT 100")
    suspend fun orphanEvidence(): List<String>
    @Query("DELETE FROM local_draft_evidence WHERE id = :id AND NOT EXISTS (SELECT 1 FROM local_drafts WHERE evidenceId = :id)")
    suspend fun deleteUnreferencedEvidence(id: String)
    @Query("UPDATE local_draft_evidence SET encryptedPayload = :payload, ciphertextSha256 = :sha256 WHERE id = :id")
    suspend fun rewrapEvidence(id: String, payload: String, sha256: String)

    @Query("SELECT * FROM local_drafts WHERE id LIKE 'ack:%' ORDER BY updatedAt LIMIT 1000") suspend fun acknowledgments(): List<LocalDraft>
    @Query("SELECT COUNT(*) FROM local_drafts WHERE id LIKE 'ack:%'") suspend fun acknowledgmentCount(): Long
    @Query("DELETE FROM local_drafts WHERE id = :id AND id LIKE 'ack:%'") suspend fun deleteAcknowledgment(id: String)
    /** Call inside the same Room transaction as its referencing row. */
    suspend fun readEvidence(id: String): LocalDraftEvidence? {
        val length = evidenceLength(id) ?: return null
        require(length in 1..12L * 1024 * 1024)
        val body = StringBuilder(length.toInt())
        var offset = 0
        while (offset < length) {
            val count = minOf(256 * 1024L, length - offset).toInt()
            val part = evidenceSlice(id, offset + 1, count) ?: error("Draft evidence changed while loading.")
            check(part.length == count)
            body.append(part); offset += count
        }
        return LocalDraftEvidence(id, body.toString(), evidenceHash(id) ?: error("Draft evidence metadata is unavailable."))
    }
}
