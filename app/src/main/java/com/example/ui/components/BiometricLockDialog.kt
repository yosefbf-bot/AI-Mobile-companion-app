package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun BiometricLockOverlay(
    onTriggerBiometrics: () -> Unit,
    error: String? = null,
    authenticationInProgress: Boolean = false,
    onOpenSecuritySettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().testTag("biometric_lock_card")) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Text("Private AI is locked", style = MaterialTheme.typography.titleLarge)
                Text("Use this phone's screen lock or biometrics to open your protected chats, documents, and desktop connection.",
                    textAlign = TextAlign.Center)
                Text("Set up an Android screen lock first if this phone does not have one.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                Button(onClick = onTriggerBiometrics, enabled = !authenticationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("unlock_biometric_button")) {
                    Text(if (authenticationInProgress) "Device authentication is open…" else "Try again")
                }
                onOpenSecuritySettings?.let { openSettings ->
                    TextButton(onClick = openSettings, enabled = !authenticationInProgress) {
                        Text("Android device security settings")
                    }
                }
            }
        }
    }
}
