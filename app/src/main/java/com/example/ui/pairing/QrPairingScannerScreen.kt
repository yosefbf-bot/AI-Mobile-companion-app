package com.example.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.crypto.EncryptionManager

@Composable
fun QrPairingScannerScreen(
    onDecoded: (String) -> Unit,
    onCancel: () -> Unit,
    onResourcesChanged: ((() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var invalidCode by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
        denied = !it
    }
    val decodedCallback by rememberUpdatedState(onDecoded)
    val cancelCallback by rememberUpdatedState(onCancel)
    BackHandler { cancelCallback() }
    Column(modifier.fillMaxSize().padding(20.dp).testTag("pairing_scanner"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan the desktop pairing QR", style = MaterialTheme.typography.headlineSmall)
        Text("On your PC, open Companion, choose this phone's permissions and show a new pairing QR. Keep the PC awake on the same approved network or VPN.")
        Text("Frames are decoded on this phone. Photos and pairing codes are not saved or sent to a scanning service.", style = MaterialTheme.typography.bodySmall)
        if (!permissionGranted) {
            CameraPermissionPanel(denied, onRequest = {
                permission.launch(Manifest.permission.CAMERA)
            }, onCancel = { cancelCallback() })
        } else if (unavailable) {
            Text("The camera could not open. Use manual pairing, or check this phone's camera permission.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { cancelCallback() }) { Text("Use manual entry") }
        } else {
            val preview = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().weight(1f))
            DisposableEffect(preview, owner) {
                val camera = PairingCameraSession(context, owner, preview,
                    authorized = EncryptionManager::hasCurrentAccess,
                    onDecoded = { decodedCallback(it) }, onStopped = { cancelCallback() },
                    onUnavailable = { unavailable = true }, onInvalidCode = { invalidCode = true })
                onResourcesChanged(camera::close)
                onDispose { camera.close(); onResourcesChanged(null) }
            }
            if (invalidCode) Text("Use the new Private AI pairing QR shown on your desktop. Other QR codes are ignored.")
            OutlinedButton(onClick = { cancelCallback() }) { Text("Cancel scanning") }
        }
    }
}

@Composable
fun CameraPermissionPanel(denied: Boolean, onRequest: () -> Unit, onCancel: () -> Unit) {
    Text(if (denied) "Camera permission was not granted. You can retry or use the secure manual link."
        else "Allow camera access to scan this desktop QR, or use manual entry.")
    Button(onClick = onRequest, modifier = Modifier.testTag("pairing_camera_permission")) { Text("Allow camera") }
    OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("pairing_camera_cancel")) { Text("Use manual entry") }
}
