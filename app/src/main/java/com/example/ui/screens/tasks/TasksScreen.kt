package com.example.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.DesktopTask
import com.example.data.model.ConnectionStatus
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<DesktopTask>,
    connectionStatus: ConnectionStatus,
    hostIp: String,
    onStartAgent: (String) -> Unit,
    onRunPlaybook: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onSubmitApproval: (String, Boolean) -> Unit,
    onRefreshTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "RUNNING", "NEEDS_APPROVAL", "COMPLETED"
    var showLauncherSheet by remember { mutableStateOf(false) }
    var selectedTaskForDetails by remember { mutableStateOf<DesktopTask?>(null) }

    val filteredTasks = remember(tasks, selectedFilter) {
        when (selectedFilter) {
            "RUNNING" -> tasks.filter { it.status == "RUNNING" }
            "NEEDS_APPROVAL" -> tasks.filter { it.requiresApproval || it.status == "NEEDS_APPROVAL" }
            "COMPLETED" -> tasks.filter { it.status == "COMPLETED" }
            else -> tasks
        }
    }

    val runningCount = tasks.count { it.status == "RUNNING" }
    val approvalCount = tasks.count { it.requiresApproval || it.status == "NEEDS_APPROVAL" }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("tasks_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Agents, Tasks & Playbooks",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Cached: $runningCount running • $approvalCount awaiting approval",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (approvalCount > 0) StatusAmber else CyberTeal,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshTasks, modifier = Modifier.testTag("refresh_tasks_button")) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh tasks")
                    }
                    ConnectionStatusBadge(
                        status = connectionStatus,
                        hostIp = hostIp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showLauncherSheet = true },
                containerColor = CyberTeal,
                contentColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("start_agent_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Start Agent or Playbook")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text("Cached desktop report metadata. Live agent launch, approvals and report controls require the PC; the phone does not expose them. Demo examples use a separate workspace.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("All (${tasks.size})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberTeal.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "RUNNING",
                    onClick = { selectedFilter = "RUNNING" },
                    label = { Text("Running ($runningCount)") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "NEEDS_APPROVAL",
                    onClick = { selectedFilter = "NEEDS_APPROVAL" },
                    label = { Text("Approval ($approvalCount)") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StatusAmber.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "COMPLETED",
                    onClick = { selectedFilter = "COMPLETED" },
                    label = { Text("Completed") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberTeal.copy(alpha = 0.2f))
                )
            }

            if (filteredTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "No tasks",
                            tint = CyberTeal.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Tasks in this Filter",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Refresh to check permitted report-job metadata. Run agents and playbooks on the PC; the '+' launcher provides isolated examples only in Demo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onCancel = { onCancelTask(task.id) },
                            onRetry = { onRetryTask(task.id) },
                            onApprove = { onSubmitApproval(task.id, true) },
                            onReject = { onSubmitApproval(task.id, false) },
                            onOpenDetails = { selectedTaskForDetails = task }
                        )
                    }
                }
            }
        }
    }

    // Agent / Playbook Launcher Bottom Sheet
    if (showLauncherSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLauncherSheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Dispatch Agent or Playbook",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Commands are sent remotely to Windows Private AI runtime. All heavy processing runs on desktop workstation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                val availableAgents = listOf(
                    "Morning Administrator Brief" to "Synthesizes clinical incidents, staffing census, and regulatory flags.",
                    "Daily Clinical Surveillance" to "Runs infection control, medication titration, and fall risk scans across EHR.",
                    "Antipsychotic Review" to "Analyzes GDR eligibility and behavioral logs for regulatory compliance.",
                    "Staffing Analysis" to "Evaluates PM/NOC staffing ratios and pre-fills float pool allocations.",
                    "Weekly Operations Review" to "Aggregates budget variance, overtime metrics, and department KPIs.",
                    "Document Analysis" to "Deep OCR, structured schema extraction, and audit report generation.",
                    "Research Agent" to "Cross-references medical literature and protocol updates locally.",
                    "Browser Agent" to "Automates secure portal checks and laboratory result verification."
                )

                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableAgents) { (name, desc) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLauncherSheet = false
                                    onStartAgent(name)
                                }
                                .testTag("launch_agent_$name"),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(CyberTeal.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Run",
                                        tint = CyberTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Task Details Modal
    selectedTaskForDetails?.let { task ->
        AlertDialog(
            onDismissRequest = { selectedTaskForDetails = null },
            title = {
                Text(text = task.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Status: ${task.status}", color = getTaskStatusColor(task.status), fontWeight = FontWeight.Bold)
                    Text(text = "Category: ${task.category}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Model: ${task.modelName}", style = MaterialTheme.typography.bodySmall, color = CyberTeal)

                    task.resultSummary?.let { summary ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text(
                        text = "Started: ${if (task.startedAt > 0) SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(task.startedAt)) else "Not provided"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTaskForDetails = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun TaskCard(
    task: DesktopTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val statusColor = getTaskStatusColor(task.status)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
            .testTag("task_card_${task.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.status,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${task.category}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Text(
                    text = if (task.category == "REPORT") "Progress unknown" else "${(task.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = CyberTeal)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            // Model badge
            Text(
                text = "Model: ${task.modelName}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = CyberTeal)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (task.status == "RUNNING" && task.category != "REPORT") {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = CyberTeal,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            task.resultSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Approval Prompt
            if (task.requiresApproval || task.status == "NEEDS_APPROVAL") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusAmber.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            text = "Approval Required",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StatusAmber)
                        )
                        task.approvalPrompt?.let { prompt ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = prompt, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onApprove,
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Approve", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = onReject,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Reject", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.status == "RUNNING") {
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Cancel, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (task.status == "FAILED") {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

fun getTaskStatusColor(status: String): androidx.compose.ui.graphics.Color {
    return when (status) {
        "RUNNING" -> CyberTeal
        "COMPLETED" -> CyberCyan
        "NEEDS_APPROVAL" -> StatusAmber
        "FAILED" -> StatusRed
        else -> StatusAmber
    }
}
