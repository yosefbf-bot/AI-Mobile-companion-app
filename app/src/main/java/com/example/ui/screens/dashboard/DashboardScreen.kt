package com.example.ui.screens.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.model.BatteryOptimizationMode
import com.example.data.model.ConnectionStatus
import com.example.data.model.NodeStats
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.SyncBanner
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusRed

@Composable
fun DashboardScreen(
    connectionStatus: ConnectionStatus,
    lastSyncTimestamp: Long,
    isSyncing: Boolean,
    batteryMode: BatteryOptimizationMode,
    nodeStats: NodeStats,
    tasks: List<DesktopTask>,
    notifications: List<DesktopNotification>,
    onSyncClick: () -> Unit,
    onNavigateChats: () -> Unit,
    onNavigateVault: () -> Unit,
    onNavigateTasks: () -> Unit,
    onNavigateAlerts: () -> Unit,
    onNavigateSettings: () -> Unit,
    onLockClick: () -> Unit,
    onNotificationClick: (String) -> Unit,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val criticalAlertsCount = notifications.count { it.severity == "CRITICAL" && !it.isAcknowledged }
    val itemsRequiringReviewCount = notifications.count { !it.isAcknowledged }
    val activeTasksCount = tasks.count { it.status == "RUNNING" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header with Private AI Wordmark & Phoenix Logo
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top row: Connection status + Lock button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConnectionStatusBadge(
                            status = connectionStatus,
                            hostIp = nodeStats.localIp,
                            onClick = onNavigateSettings
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onNavigateSettings,
                                modifier = Modifier
                                    .testTag("settings_icon_button")
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                                    .size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Settings",
                                    tint = CyberTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onLockClick,
                                modifier = Modifier
                                    .testTag("lock_vault_icon_button")
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                                    .size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Lock Secure Vault",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Brand Row with uploaded Phoenix Emblem & Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.private_ai_phoenix_icon_1790853562714),
                            contentDescription = "Private AI Phoenix Logo",
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.5.dp, CyberTeal.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "PRIVATE AI",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.2.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                text = "Windows Companion Gateway • Remote Control",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = CyberTeal,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
            }
        }

        // Sync Status Bar
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                SyncBanner(
                    lastSyncTimestamp = lastSyncTimestamp,
                    isSyncing = isSyncing,
                    batteryMode = batteryMode,
                    onSyncClick = onSyncClick
                )
            }
        }

        // Failure Handling Banner (Stage 7 Requirement)
        if (connectionStatus == ConnectionStatus.DISCONNECTED) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("pc_unavailable_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Disconnected", tint = StatusRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Private AI PC unavailable",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = StatusRed)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Possible causes:\n" +
                                    "• Not connected to work Wi-Fi\n" +
                                    "• Work VPN not connected\n" +
                                    "• PC turned off or Private AI closed\n" +
                                    "• Companion Gateway disabled or Windows Firewall blocking",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSyncClick,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Retry", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onNavigateSettings,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Connection Diagnostics", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Executive / Admin Summary Triage Cards
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Executive Overview",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Critical Alerts card
                    ExecutiveStatCard(
                        title = "Critical Alerts",
                        value = "$criticalAlertsCount",
                        subtext = if (criticalAlertsCount > 0) "Requires urgent review" else "All clear",
                        indicatorColor = if (criticalAlertsCount > 0) StatusRed else CyberTeal,
                        icon = Icons.Default.Warning,
                        onClick = onNavigateAlerts,
                        modifier = Modifier.weight(1f).testTag("stat_critical_alerts")
                    )

                    // Items Requiring Review card
                    ExecutiveStatCard(
                        title = "Review Items",
                        value = "$itemsRequiringReviewCount",
                        subtext = "Unacknowledged",
                        indicatorColor = if (itemsRequiringReviewCount > 0) StatusAmber else CyberTeal,
                        icon = Icons.Default.LocalHospital,
                        onClick = onNavigateAlerts,
                        modifier = Modifier.weight(1f).testTag("stat_review_items")
                    )

                    // Active Tasks card
                    ExecutiveStatCard(
                        title = "Active Tasks",
                        value = "$activeTasksCount",
                        subtext = "Running on PC",
                        indicatorColor = CyberCyan,
                        icon = Icons.Default.SmartToy,
                        onClick = onNavigateTasks,
                        modifier = Modifier.weight(1f).testTag("stat_active_tasks")
                    )
                }
            }
        }

        // Quick Actions (Matching ChatGPT Brief Exactly)
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            title = "Ask Private AI",
                            subtitle = "Local & Hybrid Chat",
                            icon = Icons.Default.AddComment,
                            onClick = onNavigateChats,
                            modifier = Modifier.weight(1f).testTag("quick_action_ask")
                        )
                        QuickActionButton(
                            title = "Morning Briefing",
                            subtitle = "Administrator Synthesis",
                            icon = Icons.Default.Assignment,
                            onClick = { onQuickAction("Morning Administrator Brief") },
                            modifier = Modifier.weight(1f).testTag("quick_action_morning_brief")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            title = "Run Surveillance",
                            subtitle = "Clinical Risk & Infection",
                            icon = Icons.Default.LocalHospital,
                            onClick = { onQuickAction("Daily Clinical Surveillance") },
                            modifier = Modifier.weight(1f).testTag("quick_action_surveillance")
                        )
                        QuickActionButton(
                            title = "Antipsychotic Review",
                            subtitle = "GDR Compliance Playbook",
                            icon = Icons.Default.Psychology,
                            onClick = { onQuickAction("Antipsychotic Review") },
                            modifier = Modifier.weight(1f).testTag("quick_action_antipsychotic")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            title = "Today's Tasks",
                            subtitle = "Active Agent Queue",
                            icon = Icons.Default.SmartToy,
                            onClick = onNavigateTasks,
                            modifier = Modifier.weight(1f).testTag("quick_action_tasks")
                        )
                        QuickActionButton(
                            title = "Start Agent",
                            subtitle = "Remote Automation",
                            icon = Icons.Default.PlayArrow,
                            onClick = onNavigateTasks,
                            modifier = Modifier.weight(1f).testTag("quick_action_start_agent")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            title = "Upload Document",
                            subtitle = "Send PDF / Files to PC",
                            icon = Icons.Default.UploadFile,
                            onClick = onNavigateVault,
                            modifier = Modifier.weight(1f).testTag("quick_action_upload")
                        )
                    }
                }
            }
        }

        // Recent Activity
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Activity",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "View All Tasks",
                        style = MaterialTheme.typography.labelSmall.copy(color = CyberTeal, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clickable(onClick = onNavigateTasks)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RecentActivityItem(
                            title = "Clinical Surveillance",
                            status = "Complete",
                            statusColor = CyberTeal,
                            time = "Today, 08:30"
                        )
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        RecentActivityItem(
                            title = "Weekly Analysis",
                            status = "Running",
                            statusColor = CyberCyan,
                            time = "In Progress (68%)"
                        )
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        RecentActivityItem(
                            title = "Morning Report",
                            status = "Complete",
                            statusColor = CyberTeal,
                            time = "Today, 07:15"
                        )
                    }
                }
            }
        }

        // Workstation Node Hardware Telemetry
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Windows Workstation Telemetry",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Host Node:", style = MaterialTheme.typography.labelSmall)
                            Text(text = nodeStats.serverName, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Primary Model:", style = MaterialTheme.typography.labelSmall)
                            Text(text = nodeStats.loadedModel, style = MaterialTheme.typography.labelSmall.copy(color = CyberTeal, fontWeight = FontWeight.Bold))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "GPU VRAM Allocated:", style = MaterialTheme.typography.labelSmall)
                            Text(text = "${nodeStats.vramUsedGb} GB / ${nodeStats.vramTotalGb} GB (RTX 4090)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Transport Path:", style = MaterialTheme.typography.labelSmall)
                            Text(text = "LAN / VPN Direct Gateway", style = MaterialTheme.typography.labelSmall.copy(color = CyberCyan))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExecutiveStatCard(
    title: String,
    value: String,
    subtext: String,
    indicatorColor: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, indicatorColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = indicatorColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = indicatorColor
                )
            )

            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun QuickActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(CyberTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = CyberTeal,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun RecentActivityItem(
    title: String,
    status: String,
    statusColor: Color,
    time: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "• $time",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}
