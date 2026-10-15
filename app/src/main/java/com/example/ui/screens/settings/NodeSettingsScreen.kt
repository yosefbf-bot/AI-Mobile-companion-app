package com.example.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.pairing.PairingUiState
import com.example.ui.theme.Appearance
import com.example.ui.components.ConnectionStatusBadge

@Composable
fun NodeSettingsScreen(
    currentConfig: DesktopNodeConfig, connectionStatus: ConnectionStatus, secureClinicalMode: Boolean,
    onSaveConfig: (DesktopNodeConfig) -> Unit, onToggleSecureClinicalMode: (Boolean) -> Unit,
    onTestConnection: () -> Unit, onLockVault: () -> Unit, modifier: Modifier = Modifier,
    onRequestNotificationPermission: () -> Unit = {},
    appearance: Appearance = Appearance.DARK, onAppearanceChange: (Appearance) -> Unit = {},
    pairingState: PairingUiState = PairingUiState(), onPairLink: (String) -> Unit = {},
    onScanPairingQr: () -> Unit = {}, onCancelPairing: () -> Unit = {}
) {
    var pairingText by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { pairingText = "" } }
    LazyColumn(modifier.fillMaxSize().testTag("node_settings_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { option ->
                    FilterChip(selected = appearance == option, onClick = { onAppearanceChange(option) },
                        label = { Text(option.label) }, modifier = Modifier.testTag("appearance_" + option.name.lowercase()))
                }
            }
        }
        item {
            Text("Desktop connection", style = MaterialTheme.typography.headlineSmall)
            ConnectionStatusBadge(connectionStatus, currentConfig.hostIp)
            Text(if (currentConfig.simulatedNodeEnabled) "This is an isolated demonstration. It performs no work on the PC."
                else "The PC owns the AI, conversations, clinical review and actions. The phone sends only the requests you choose.")
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pair this phone", style = MaterialTheme.typography.titleMedium)
                    Text("On the desktop, open Companion, choose this phone's permissions and show a new pairing QR. Keep the PC awake on the same approved private network or a VPN that can reach it.")
                    Button(onClick = { pairingText = ""; onScanPairingQr() },
                        enabled = !pairingState.connecting && !currentConfig.simulatedNodeEnabled,
                        modifier = Modifier.testTag("scan_pairing_qr")) { Text("Scan desktop QR") }
                    Text("Or copy the secure pairing link from the desktop and paste it below. A QR or link is a secret; do not share it. Existing full-certificate pairing links remain supported.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pairingText, { pairingText = it }, label = { Text("Private desktop pairing link") },
                        enabled = !pairingState.connecting && !currentConfig.simulatedNodeEnabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("pairing_link_input"))
                    Button(onClick = { val link = pairingText; pairingText = ""; onPairLink(link) },
                        enabled = pairingText.isNotBlank() && !pairingState.connecting && !currentConfig.simulatedNodeEnabled,
                        modifier = Modifier.testTag("save_config_button")) { Text("Connect securely") }
                    if (currentConfig.simulatedNodeEnabled) Text("Turn off Demo before pairing with a real desktop.")
                    if (pairingState.connecting) {
                        Text("Verifying the desktop certificate and this phone's approved access…")
                        OutlinedButton(onClick = onCancelPairing) { Text("Cancel pairing") }
                    }
                    pairingState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    pairingState.completion?.let {
                        Text("Pairing verified with ${it.serverName}. Authenticated desktop access was checked.")
                        Text("Approved permissions: ${it.approvedScopes.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (currentConfig.isPaired) {
                        Text("Desktop: ${currentConfig.hostIp}:${currentConfig.port}")
                        Text("Paired certificate: ${currentConfig.certificateFingerprint.take(12)}…")
                        Text("If the address or certificate changes, pair again. No shared default key or accept-all certificate mode is used.")
                    } else Text("No real desktop has been paired.")
                    Text("If the PC cannot be reached, check its Companion connection, address, Windows firewall and approved Wi-Fi/VPN. Show a fresh QR if it expired. Certificate or access errors require desktop review; no trust bypass is offered.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onTestConnection, enabled = currentConfig.isPaired && !pairingState.connecting) { Text("Check desktop") }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permissions and notifications", style = MaterialTheme.typography.titleMedium)
                    Text("Permissions are granted on the desktop for this specific phone. Chat, report-job metadata, clinical-alert metadata, workflow capture and follow-up notifications are independent.")
                    Text("Clinical alert metadata is not an all-clear. Review resident details and clinical evidence on the PC. Drafts are review items and are not sent by saving them.")
                    Text("The desktop must be awake and reachable. A locked desktop may deny access. Companion access does not wake or unlock it.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(currentConfig.backgroundNotificationsEnabled, onCheckedChange = {
                            onSaveConfig(currentConfig.copy(backgroundNotificationsEnabled = it))
                            if (it) onRequestNotificationPermission()
                        }, enabled = currentConfig.notificationKey.isNotBlank() && !currentConfig.simulatedNodeEnabled)
                        Text("Background follow-up notices")
                    }
                    Text("Optional generic notices use a separate notification-only key. No chat, notes, resident details or remote actions are accessible with that key. Allow notifications when Android asks.")
                    Text("Background notices use a separate periodic schedule with a 15-minute minimum. Android may delay checks when the battery is low or background work is restricted. These are not immediate alerts or emergency paging.")
                    if (currentConfig.notificationKey.isBlank()) Text("Pair again with the desktop's Notifications permission to enable this option.")
                }
            }
        }
        item {
            Text("Checks while the phone is unlocked", style = MaterialTheme.typography.titleMedium)
            BatteryOptimizationMode.entries.forEach { mode ->
                Row {
                    RadioButton(currentConfig.batteryMode == mode, onClick = { onSaveConfig(currentConfig.copy(batteryMode = mode)) })
                    Column {
                        Text(mode.title)
                        Text(if (mode == BatteryOptimizationMode.MANUAL_ONLY)
                            "Use Sync now to refresh. Opening the workspace or changing connection settings can still check the connection."
                            else mode.description, style = MaterialTheme.typography.bodySmall)
                        Text(when (mode) {
                            BatteryOptimizationMode.REAL_TIME -> "Most responsive; higher battery and network use."
                            BatteryOptimizationMode.SMART_ECO -> "Balanced; lower battery and network use (recommended)."
                            BatteryOptimizationMode.MANUAL_ONLY -> "Lowest automatic checking overhead; no periodic full-access checks."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Relative estimates only. Actual battery life varies by device, network and Android background limits.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(currentConfig.simulatedNodeEnabled, onCheckedChange = { onSaveConfig(currentConfig.copy(simulatedNodeEnabled = it)) })
                Column { Text("Isolated demo workspace"); Text("Examples are labeled and kept in a separate database. Live data stays in the real workspace.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Text("Privacy & Unlock", style = MaterialTheme.typography.titleMedium)
            Text("Screenshots and app previews are blocked for the protected phone workspace.")
            Text("Require unlock again while actively using the app:")
            listOf("5 minutes" to "5", "10 minutes" to "10", "20 minutes" to "20", "30 minutes" to "30", "Off while active" to "off").forEach { (label, tag) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = tag == "5", onClick = {}, enabled = tag == "5",
                        modifier = Modifier.testTag("session_mode_" + tag))
                    Text(label)
                }
            }
            Text("Five minutes from Android authentication is the current security limit, even while you keep using the app. Longer sessions and Off need a verified protected-storage upgrade and are unavailable. Your existing keys and saved records are unchanged.", style = MaterialTheme.typography.bodySmall)
            Text("Backgrounding, phone lock, Lock now and Android security expiry can require unlock sooner. Polling and typing do not extend the session.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(secureClinicalMode, onCheckedChange = onToggleSecureClinicalMode)
                Column {
                    Text("Require local-only and HIPAA-mode phone chats")
                    Text("Adds processing restrictions to phone chat requests. Turn this off only for approved general inquiries. Desktop HIPAA, provider approval and organization policy still apply; screenshot protection stays on.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onLockVault) { Text("Lock phone workspace") }
        }
    }
}
