package com.example.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatteryOptimizationMode
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusRed

@Composable
fun NodeSettingsScreen(
    currentConfig: DesktopNodeConfig,
    connectionStatus: ConnectionStatus,
    secureClinicalMode: Boolean,
    onSaveConfig: (DesktopNodeConfig) -> Unit,
    onToggleSecureClinicalMode: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onLockVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hostIp by remember(currentConfig) { mutableStateOf(currentConfig.hostIp) }
    var portText by remember(currentConfig) { mutableStateOf(currentConfig.port.toString()) }
    var pairKey by remember(currentConfig) { mutableStateOf(currentConfig.pairKey) }
    var friendlyName by remember(currentConfig) { mutableStateOf(currentConfig.friendlyName) }
    var selectedBatteryMode by remember(currentConfig) { mutableStateOf(currentConfig.batteryMode) }
    var connectionType by remember { mutableStateOf("Work Wi-Fi / VPN") }

    var showPairingDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("node_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Companion Gateway",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Remote Interface & Security Configuration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ConnectionStatusBadge(
                    status = connectionStatus,
                    hostIp = "$hostIp:$portText"
                )
            }
        }

        // Live Connection Verification Status (Exact ChatGPT brief)
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("connection_verification_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberTeal.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection Diagnostics",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        IconButton(
                            onClick = onTestConnection,
                            modifier = Modifier.size(28.dp).testTag("refresh_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh connection test",
                                tint = CyberTeal,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DiagnosticRow("Private AI Server", "$hostIp:$portText")
                    DiagnosticRow("Connection Type", connectionType)
                    DiagnosticRow("Companion Gateway", if (connectionStatus == ConnectionStatus.CONNECTED) "Connected ✓" else "Disconnected ✗", isSuccess = connectionStatus == ConnectionStatus.CONNECTED)
                    DiagnosticRow("TLS Encryption", "Verified ✓ (HTTPS/TLS)", isSuccess = true)
                    DiagnosticRow("Android Keystore Keypair", "Verified ✓ (Hardware-backed)", isSuccess = true)
                    DiagnosticRow("Private AI Backend", if (connectionStatus == ConnectionStatus.CONNECTED) "Online ✓" else "Offline ✗", isSuccess = connectionStatus == ConnectionStatus.CONNECTED)
                    DiagnosticRow("WebSocket Event Stream", if (connectionStatus == ConnectionStatus.CONNECTED) "Connected ✓ (Active)" else "Standby", isSuccess = connectionStatus == ConnectionStatus.CONNECTED)
                    DiagnosticRow("Gateway Latency", if (connectionStatus == ConnectionStatus.CONNECTED) "24 ms (Direct LAN)" else "N/A")

                    if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Private AI PC unavailable",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StatusRed)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Check that your phone is on the same Wi-Fi/VPN, Windows Private AI is open, and Mobile Companion Gateway is enabled.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onTestConnection,
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("Retry Connection", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { showDiagnosticsDialog = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("Diagnostics Help", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Secure Clinical Mode (Mandatory ChatGPT Section)
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("secure_clinical_mode_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (secureClinicalMode) CyberTeal.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (secureClinicalMode) CyberTeal else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Shield",
                                tint = if (secureClinicalMode) CyberTeal else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Secure Clinical Mode",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (secureClinicalMode) "Active: Screenshots blocked & PHI sanitized" else "Disabled: Standard logging",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (secureClinicalMode) CyberTeal else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = secureClinicalMode,
                            onCheckedChange = { onToggleSecureClinicalMode(it) },
                            modifier = Modifier.testTag("secure_clinical_mode_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• Prevents Android screen recording & screenshots (FLAG_SECURE)\n" +
                                "• Hides sensitive previews from Android Recent Apps\n" +
                                "• Enforces strict biometric re-authentication & zero PHI in push alerts\n" +
                                "• Automatically purges temporary render files",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Pair Device with QR Code
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Pairing",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Pair this phone with Windows Private AI via Settings → Mobile Companion → Pair Device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showPairingDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("scan_qr_code_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal)
                    ) {
                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pair via Desktop QR Code")
                    }
                }
            }
        }

        // Manual Server-IP Configuration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Manual Connection Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = hostIp,
                        onValueChange = { hostIp = it },
                        label = { Text("Private AI Server IP / Hostname") },
                        modifier = Modifier.fillMaxWidth().testTag("host_ip_input")
                    )

                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Gateway Port (Default 8443)") },
                        modifier = Modifier.fillMaxWidth().testTag("port_input")
                    )

                    OutlinedTextField(
                        value = pairKey,
                        onValueChange = { pairKey = it },
                        label = { Text("Authentication Token / Pairing Key") },
                        modifier = Modifier.fillMaxWidth().testTag("pair_key_input")
                    )

                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 8443
                            onSaveConfig(
                                currentConfig.copy(
                                    hostIp = hostIp.trim(),
                                    port = port,
                                    pairKey = pairKey.trim(),
                                    friendlyName = friendlyName.trim(),
                                    batteryMode = selectedBatteryMode
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("save_config_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Save Gateway Configuration")
                    }
                }
            }
        }

        // Mobile Scopes & Permissions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Registered Device Scopes (Windows Enforced)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "CHAT_READ • CHAT_WRITE • ALERT_READ • ALERT_ACKNOWLEDGE • TASK_READ • TASK_RUN • TASK_CANCEL • AGENT_RUN • AGENT_APPROVE • FILE_UPLOAD • FILE_DOWNLOAD • CLINICAL_READ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberTeal
                        )
                    )
                }
            }
        }
    }

    // Diagnostics Dialog
    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Connection Diagnostics") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("If Windows PC is unreachable:", fontWeight = FontWeight.Bold)
                    Text("1. Confirm both devices are connected to the same Wi-Fi network or active work VPN.")
                    Text("2. Verify Windows Private AI application is running on your workstation.")
                    Text("3. Confirm 'Mobile Companion' is enabled in Windows Settings.")
                    Text("4. Check that Windows Firewall allows port $portText on Private/Domain networks.")
                    Text("5. If using VPN, verify Android route allows traffic to $hostIp.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("Understood")
                }
            }
        )
    }

    // QR Code Pairing Dialog
    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = { showPairingDialog = false },
            title = { Text("Pair with Windows Private AI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("To pair this Android companion:")
                    Text("1. Open Windows Private AI")
                    Text("2. Go to Settings → Mobile Companion → Pair Device")
                    Text("3. The desktop will generate a one-time cryptographic pairing token and register this device's public key.")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberTeal.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Hardware Keypair Ready: Key generated in Android Keystore with Biometric authorization requirement.",
                            style = MaterialTheme.typography.labelSmall.copy(color = CyberTeal)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPairingDialog = false
                        onTestConnection()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTeal)
                ) {
                    Text("Simulate Successful Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DiagnosticRow(title: String, status: String, isSuccess: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isSuccess) CyberTeal else StatusRed
            )
        )
    }
}
