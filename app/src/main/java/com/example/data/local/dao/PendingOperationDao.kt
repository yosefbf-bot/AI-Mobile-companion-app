package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.PendingOperation

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' AND operation LIKE 'MEETING_%' AND desktopFingerprint = :fingerprint ORDER BY createdAt, rowid LIMIT 1")
    suspend fun nextMeeting(fingerprint: String): PendingOperation?

    @Query("SELECT COALESCE(SUM(length(encryptedPayload)),0) FROM pending_operations WHERE status = 'PENDING' AND operation LIKE 'MEETING_%'")
    suspend fun meetingBytes(): Long

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING' AND operation LIKE 'MEETING_%' AND desktopFingerprint = :fingerprint")
    suspend fun meetingCount(fingerprint: String): Int

    @Query("SELECT * FROM pending_operations WHERE operation = 'MEETING_STATE' AND desktopFingerprint = :fingerprint ORDER BY createdAt DESC LIMIT 100")
    suspend fun meetingStates(fingerprint: String): List<PendingOperation>

    @Query("SELECT * FROM pending_operations WHERE id = :id AND operation = 'MEETING_STATE'")
    suspend fun meetingState(id: String): PendingOperation?

    @Query("UPDATE pending_operations SET encryptedPayload = :payload WHERE id = :id AND operation = 'MEETING_STATE'")
    suspend fun updateMeetingState(id: String, payload: String)

    @Query("UPDATE pending_operations SET status = 'ACKNOWLEDGED', encryptedPayload = :receipt, serverId = :serverId WHERE id = :id AND status = 'PENDING'")
    suspend fun acknowledgeMeeting(id: String, serverId: String, receipt: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(operation: PendingOperation)

    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' AND desktopFingerprint = :fingerprint ORDER BY createdAt ASC")
    suspend fun pending(fingerprint: String): List<PendingOperation>

    @Query("UPDATE pending_operations SET attemptedAt = :at WHERE id = :id AND status = 'PENDING'")
    suspend fun attempted(id: String, at: Long)

    @Query("UPDATE pending_operations SET status = 'ACKNOWLEDGED', serverId = :serverId WHERE id = :id AND status = 'PENDING'")
    suspend fun acknowledge(id: String, serverId: String?)
}
