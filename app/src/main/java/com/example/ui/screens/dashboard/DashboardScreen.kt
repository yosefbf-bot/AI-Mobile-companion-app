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
    workflows: com.example.data.model.WorkflowSnapshot,
    onNavigateNotes: () -> Unit,
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
    val open = workflows.actions.filter { it.status != "completed" }
    val pendingAlerts = notifications.count { !it.isAcknowledged }
    LazyColumn(modifier.fillMaxSize().testTag("dashboard_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onNavigateSettings, modifier = Modifier.testTag("settings_icon_button")) { Icon(Icons.Default.Tune, contentDescription = "Settings") }
                IconButton(onClick = onLockClick, modifier = Modifier.testTag("lock_vault_icon_button")) { Icon(Icons.Default.Lock, contentDescription = "Lock") }
            }
            ConnectionStatusBadge(status = connectionStatus, hostIp = nodeStats.localIp, onClick = onNavigateSettings)
            Text(if (lastSyncTimestamp > 0) "Last synced: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastSyncTimestamp))}" else "No desktop sync yet. Counts reflect available records only.")
            OutlinedButton(onClick = onSyncClick, enabled = !isSyncing) { Text("Refresh") }
            if (isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Text("Follow-ups", style = MaterialTheme.typography.titleLarge)
            Text("${open.size} open · $pendingAlerts alerts to review")
            Button(onClick = onNavigateNotes) { Text("Add a note or review follow-ups") }
            if (open.isEmpty()) Text("No follow-ups in the available records.")
        }
        items(open.take(20), key = { it.id }) { action ->
            Card(Modifier.fillMaxWidth().clickable(onClick = onNavigateNotes)) { Column(Modifier.padding(16.dp)) {
                Text(action.title, style = MaterialTheme.typography.titleMedium)
                Text(action.owner.ifBlank { "Unassigned" })
                Text(action.dueAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "No deadline")
            } }
        }
        item {
            Text("Alerts", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onNavigateAlerts) { Text("Review alerts ($pendingAlerts)") }
            Text("No recorded alerts does not establish complete monitoring.")
        }
        item {
            OutlinedButton(onClick = onNavigateTasks) { Text("Background tasks (${tasks.count { it.status == "RUNNING" }})") }
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
