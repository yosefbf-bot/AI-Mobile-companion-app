package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "desktop_tasks")
data class DesktopTask(
    @PrimaryKey
    val id: String,
    val title: String,
    val status: String, // "RUNNING", "COMPLETED", "QUEUED", "FAILED", "NEEDS_APPROVAL"
    val category: String = "AGENT", // "AGENT", "PLAYBOOK", "SURVEILLANCE", "CLINICAL", "OPERATIONS"
    val progress: Float = 0f,
    val modelName: String = "Llama-3.3-70B-Local",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val resultSummary: String? = null,
    val requiresApproval: Boolean = false,
    val approvalPrompt: String? = null
)

@Entity(tableName = "desktop_notifications")
data class DesktopNotification(
    @PrimaryKey
    val id: String,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val isAcknowledged: Boolean = false,
    val severity: String = "MEDIUM", // "CRITICAL", "HIGH", "MEDIUM", "INFO"
    val category: String = "CLINICAL", // "CLINICAL", "OPERATIONAL", "AGENT_APPROVAL", "SURVEILLANCE", "TASK_STATUS"
    val requiresApproval: Boolean = false,
    val assignedTo: String? = null,
    val relatedAction: String? = null
)
