package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.PendingOperation

@Dao
interface PendingOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(operation: PendingOperation)

    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' AND desktopFingerprint = :fingerprint ORDER BY createdAt ASC")
    suspend fun pending(fingerprint: String): List<PendingOperation>

    @Query("UPDATE pending_operations SET attemptedAt = :at WHERE id = :id AND status = 'PENDING'")
    suspend fun attempted(id: String, at: Long)

    @Query("UPDATE pending_operations SET status = 'ACKNOWLEDGED', serverId = :serverId WHERE id = :id AND status = 'PENDING'")
    suspend fun acknowledge(id: String, serverId: String?)
}
