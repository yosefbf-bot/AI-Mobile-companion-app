package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM desktop_tasks WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun tasksAfter(after: String?, limit: Int): List<DesktopTask>
    @Query("SELECT * FROM desktop_notifications WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun notificationsAfter(after: String?, limit: Int): List<DesktopNotification>

    @Query("SELECT * FROM desktop_tasks")
    suspend fun allTasksForMigration(): List<DesktopTask>

    @Query("SELECT * FROM desktop_notifications")
    suspend fun allNotificationsForMigration(): List<DesktopNotification>

    @Query("SELECT * FROM desktop_tasks WHERE storageVersion = 0")
    suspend fun legacyTasks(): List<DesktopTask>
    @Query("SELECT * FROM desktop_notifications WHERE storageVersion = 0")
    suspend fun legacyNotifications(): List<DesktopNotification>

    @Query("SELECT * FROM desktop_notifications WHERE id = :id")
    suspend fun notificationById(id: String): DesktopNotification?

    @Transaction
    suspend fun mergeNotificationsPreservingLocalState(rows: List<DesktopNotification>) {
        for (row in rows) {
            val previous = notificationById(row.id)
            insertNotification(row.copy(
                isRead = previous?.isRead == true || row.isRead,
                isAcknowledged = previous?.isAcknowledged == true || row.isAcknowledged
            ))
        }
    }

    @Query("SELECT * FROM desktop_tasks ORDER BY startedAt DESC")
    fun getAllTasks(): Flow<List<DesktopTask>>

    @Query("SELECT * FROM desktop_tasks WHERE id = :id")
    fun getTaskById(id: String): Flow<DesktopTask?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DesktopTask)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<DesktopTask>)

    @Update
    suspend fun updateTask(task: DesktopTask)

    @Query("UPDATE desktop_tasks SET status = :status WHERE id = :id")
    suspend fun updateTaskStatus(id: String, status: String)

    @Query("DELETE FROM desktop_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM desktop_tasks")
    suspend fun clearAllTasks()

    @Query("SELECT * FROM desktop_notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<DesktopNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: DesktopNotification)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<DesktopNotification>)

    @Query("UPDATE desktop_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationRead(id: String)

    @Query("UPDATE desktop_notifications SET isAcknowledged = 1, isRead = 1 WHERE id = :id")
    suspend fun acknowledgeNotification(id: String)

    @Query("UPDATE desktop_notifications SET isRead = 1")
    suspend fun markAllNotificationsRead()

    @Query("DELETE FROM desktop_notifications")
    suspend fun clearAllNotifications()
}
