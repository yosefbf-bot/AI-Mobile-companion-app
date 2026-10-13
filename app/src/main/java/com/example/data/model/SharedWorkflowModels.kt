package com.example.data.model

data class WorkflowAction(
    val id: String, val title: String, val description: String, val owner: String,
    val dueAt: Long?, val status: String, val revision: String, val captureId: String?,
    val reviewRequired: Boolean, val unknownFields: List<String>
)
data class WorkflowDraft(val id: String, val captureId: String?, val channel: String, val body: String, val recipient: String, val status: String, val revision: Long, val subject: String = "")
data class WorkflowCapture(val id: String, val text: String, val kind: String, val location: String, val created: Long, val evidenceIds: List<String>)
data class FollowUpNotification(val id: String, val actionId: String?, val created: Long, val status: String)
data class WorkflowSnapshot(
    val captures: List<WorkflowCapture> = emptyList(), val actions: List<WorkflowAction> = emptyList(),
    val drafts: List<WorkflowDraft> = emptyList(), val notifications: List<FollowUpNotification> = emptyList(),
    val truncated: Boolean = false
)

data class DesktopMeeting(val sourceId: String, val title: String, val status: String, val pendingAudio: Int, val summary: String, val actions: List<String>)
