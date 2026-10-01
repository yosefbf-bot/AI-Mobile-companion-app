package com.example.ui.screens.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.DesktopNotification
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
fun AlertsScreen(
    alerts: List<DesktopNotification>,
    connectionStatus: ConnectionStatus,
    hostIp: String,
    onAcknowledgeAlert: (String) -> Unit,
    onApproveAction: (String, Boolean) -> Unit,
    onRunAnalysis: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "UNRESOLVED", "CRITICAL", "APPROVALS"
    var selectedAlertForDetails by remember { mutableStateOf<DesktopNotification?>(null) }

    val filteredAlerts = remember(alerts, selectedFilter) {
        when (selectedFilter) {
            "UNRESOLVED" -> alerts.filter { !it.isAcknowledged }
            "CRITICAL" -> alerts.filter { it.severity == "CRITICAL" || it.severity == "HIGH" }
            "APPROVALS" -> alerts.filter { it.requiresApproval }
            else -> alerts
        }
    }

    val unacknowledgedCount = alerts.count { !it.isAcknowledged }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("alerts_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Clinical & Operational Alerts",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (unacknowledgedCount > 0) "$unacknowledgedCount unresolved items require review" else "All alerts acknowledged",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (unacknowledgedCount > 0) StatusRed else CyberTeal,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                },
                actions = {
                    if (alerts.any { !it.isRead }) {
                        IconButton(
                            onClick = onMarkAllRead,
                            modifier = Modifier.testTag("mark_all_read_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Mark all read",
                                tint = CyberTeal
                            )
                        }
                    }
                    ConnectionStatusBadge(
                        status = connectionStatus,
                        hostIp = hostIp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                    label = { Text("All (${alerts.size})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberTeal.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "UNRESOLVED",
                    onClick = { selectedFilter = "UNRESOLVED" },
                    label = { Text("Unresolved ($unacknowledgedCount)") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StatusRed.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "CRITICAL",
                    onClick = { selectedFilter = "CRITICAL" },
                    label = { Text("Critical") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StatusAmber.copy(alpha = 0.2f))
                )
                FilterChip(
                    selected = selectedFilter == "APPROVALS",
                    onClick = { selectedFilter = "APPROVALS" },
                    label = { Text("Approvals") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f))
                )
            }

            if (filteredAlerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(CyberTeal.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Clear",
                                tint = CyberTeal,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Alerts in This Category",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Surveillance and clinical agent pipelines running normally on Windows host.",
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
                    items(filteredAlerts, key = { it.id }) { alert ->
                        AlertCard(
                            alert = alert,
                            onAcknowledge = { onAcknowledgeAlert(alert.id) },
                            onOpenDetails = { selectedAlertForDetails = alert },
                            onApprove = { onApproveAction(alert.id, true) },
                            onReject = { onApproveAction(alert.id, false) },
                            onRunAnalysis = { onRunAnalysis(alert.relatedAction ?: alert.title) }
                        )
                    }
                }
            }
        }
    }

    // Alert Details Dialog (PHI Safe)
    selectedAlertForDetails?.let { alert ->
        AlertDialog(
            onDismissRequest = { selectedAlertForDetails = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = getSeverityIcon(alert.severity),
                        contentDescription = alert.severity,
                        tint = getSeverityColor(alert.severity)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Severity: ${alert.severity}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = getSeverityColor(alert.severity)
                        )
                        Text(
                            text = "Category: ${alert.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTeal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = alert.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    alert.assignedTo?.let { assignee ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AssignmentInd,
                                contentDescription = "Assigned",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Assigned Follow-up: $assignee",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                    }

                    Text(
                        text = "Timestamp: ${SimpleDateFormat("MMM d, yyyy • HH:mm:ss", Locale.getDefault()).format(Date(alert.timestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberTeal.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "🔒 Zero-Cloud Guarantee: Clinical surveillance logic evaluated directly on local desktop host. Identifiers stripped in transit.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = CyberTeal
                        )
                    }
                }
            },
            confirmButton = {
                if (!alert.isAcknowledged) {
                    Button(
                        onClick = {
                            onAcknowledgeAlert(alert.id)
                            selectedAlertForDetails = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal)
                    ) {
                        Text("Acknowledge")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAlertForDetails = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun AlertCard(
    alert: DesktopNotification,
    onAcknowledge: () -> Unit,
    onOpenDetails: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRunAnalysis: () -> Unit
) {
    val severityColor = getSeverityColor(alert.severity)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alert_card_${alert.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (!alert.isAcknowledged) 1.5.dp else 1.dp,
            color = if (!alert.isAcknowledged) severityColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
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
                            .background(severityColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = alert.severity,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = severityColor
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${alert.category}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                if (alert.isAcknowledged) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Acknowledged",
                            tint = CyberTeal,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Acknowledged",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = CyberTeal)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(StatusRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ACTION REQUIRED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusRed
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = alert.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = alert.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenDetails,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Details",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Details", style = MaterialTheme.typography.labelSmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alert.requiresApproval) {
                        Button(
                            onClick = onApprove,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Approve", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Approve", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = onReject,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reject", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (!alert.isAcknowledged) {
                        Button(
                            onClick = onAcknowledge,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Acknowledge", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Acknowledge", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (alert.relatedAction != null) {
                        OutlinedButton(
                            onClick = onRunAnalysis,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run Analysis", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

fun getSeverityColor(severity: String): Color {
    return when (severity) {
        "CRITICAL" -> StatusRed
        "HIGH" -> StatusAmber
        "MEDIUM" -> CyberCyan
        else -> CyberTeal
    }
}

fun getSeverityIcon(severity: String): ImageVector {
    return when (severity) {
        "CRITICAL" -> Icons.Default.Warning
        "HIGH" -> Icons.Default.Warning
        "MEDIUM" -> Icons.Default.Info
        else -> Icons.Default.CheckCircle
    }
}
