package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "desktop_tasks")
data class DesktopTask(
    @PrimaryKey
    val id: String,
    val title: String,
    val status: String, // "RUNNING", "COMPLETED", "QUEUED", "FAILED"
    val progress: Float = 0f,
    val modelName: String = "Llama-3-70B-Local",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val resultSummary: String? = null
)

@Entity(tableName = "desktop_notifications")
data class DesktopNotification(
    @PrimaryKey
    val id: String,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val type: String = "INFERENCE_COMPLETE" // "INFERENCE_COMPLETE", "TASK_FINISHED", "SYNC_ALERT", "SECURITY_EVENT"
)
