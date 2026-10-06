package com.example

import android.os.Bundle
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.components.BiometricLockOverlay
import com.example.ui.screens.alerts.AlertsScreen
import com.example.ui.screens.chats.ChatDetailScreen
import com.example.ui.screens.chats.ChatListScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.settings.NodeSettingsScreen
import com.example.ui.screens.tasks.TasksScreen
import com.example.ui.screens.vault.DocumentVaultScreen
import com.example.ui.screens.workflows.FacilityCaptureScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.data.crypto.AttachmentUtils
import com.example.data.crypto.EncryptionManager
import com.example.data.notifications.FollowUpNotifications
import com.example.data.network.PairingConfigParser
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusRed

enum class ScreenTab(val title: String, val testTag: String) {
    HOME("Home", "nav_home"),
    CHAT("Chat", "nav_chat"),
    TASKS("Tasks", "nav_tasks"),
    ALERTS("Alerts", "nav_alerts"),
    FILES("Files", "nav_files"),
    SETTINGS("Settings", "nav_settings"),
    CAPTURE("Notes & follow-ups", "nav_capture")
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var authenticationError by mutableStateOf<String?>(null)
    private data class ExternalTarget(val route: String, val fingerprint: String, val demo: Boolean)
    private data class ExternalResult(val target: ExternalTarget, val uri: Uri? = null, val bitmap: Bitmap? = null)
    private var externalTarget: ExternalTarget? = null
    private var pendingExternal by mutableStateOf<ExternalResult?>(null)
    private var evidenceError by mutableStateOf<String?>(null)
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        externalTarget?.let { target -> if (uri != null) pendingExternal = ExternalResult(target, uri = uri) }
        externalTarget = null
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        externalTarget?.let { target -> if (bitmap != null) pendingExternal = ExternalResult(target, bitmap = bitmap) }
        externalTarget = null
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && externalTarget != null) camera.launch(null)
        else { externalTarget = null; evidenceError = "Camera access was not granted." }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) FollowUpNotifications.configure(this, viewModel.nodeConfig.value.backgroundNotificationsEnabled)
        else evidenceError = "Notification permission is off. Follow-ups remain pending on the desktop."
    }

    private fun target(route: String): ExternalTarget? {
        if (!viewModel.vaultReady.value || !EncryptionManager.isUnlocked.value) return null
        val config = viewModel.nodeConfig.value
        return ExternalTarget(route, PairingConfigParser.destinationIdentity(config), config.simulatedNodeEnabled)
    }
    private fun pickEvidence(route: String, mime: String) {
        externalTarget = target(route) ?: return
        filePicker.launch(mime)
    }
    private fun takePhoto(route: String) {
        externalTarget = target(route) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            camera.launch(null)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private val deviceCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK ||
            !viewModel.biometricAuthManager.completeDeviceCredentialAuthentication()) {
            authenticationError = "Device authentication did not unlock the vault. Existing data was preserved."
        } else authenticationError = null
    }

    private fun authenticate() {
        authenticationError = null
        val auth = viewModel.biometricAuthManager
        if (auth.requiresLegacyDeviceCredential) {
            val intent = auth.deviceCredentialIntent()
            if (intent == null) authenticationError = "Set up this phone's Android screen lock before opening Private AI."
            else deviceCredential.launch(intent)
        } else auth.promptBiometricAuth(this, onSuccess = { authenticationError = null },
            onError = { authenticationError = it })
    }

    override fun onStop() {
        viewModel.lockVault()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
                val vaultReady by viewModel.vaultReady.collectAsStateWithLifecycle()
                val vaultError by viewModel.lastError.collectAsStateWithLifecycle()
                val workflowState by viewModel.workflowState.collectAsStateWithLifecycle()
                val workflowBusy by viewModel.workflowBusy.collectAsStateWithLifecycle()
                val lastSavedCaptureId by viewModel.lastSavedCaptureId.collectAsStateWithLifecycle()
                val pendingEvidence by viewModel.pendingEvidence.collectAsStateWithLifecycle()
                val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
                val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
                val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
                val nodeConfig by viewModel.nodeConfig.collectAsStateWithLifecycle()
                val nodeStats by viewModel.nodeStats.collectAsStateWithLifecycle()
                val chatSessions by viewModel.chatSessions.collectAsStateWithLifecycle()
                val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
                val activeSessionMessages by viewModel.activeSessionMessages.collectAsStateWithLifecycle()
                val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
                val documents by viewModel.documents.collectAsStateWithLifecycle()
                val tasks by viewModel.tasks.collectAsStateWithLifecycle()
                val notifications by viewModel.notifications.collectAsStateWithLifecycle()
                val feedbackMessage by viewModel.userFeedback.collectAsStateWithLifecycle()
                val secureClinicalMode by viewModel.secureClinicalMode.collectAsStateWithLifecycle()
                val modelMode by viewModel.modelProcessingMode.collectAsStateWithLifecycle()
                val isStreamingResponse by viewModel.isStreamingResponse.collectAsStateWithLifecycle()

                val snackbarHostState = remember { SnackbarHostState() }
                var currentTab by remember { mutableStateOf(ScreenTab.HOME) }
                val chatRoute = "chat:" + activeSessionId.orEmpty()

                LaunchedEffect(pendingExternal, vaultReady) {
                    val received = pendingExternal
                    if (received != null && vaultReady && isUnlocked) {
                        var processed = false
                        try {
                            if (received.target.fingerprint != PairingConfigParser.destinationIdentity(nodeConfig) ||
                                received.target.demo != nodeConfig.simulatedNodeEnabled) {
                                evidenceError = "Desktop pairing changed. Select the evidence again for the current workspace."
                                processed = true
                            } else {
                                val attachment = withContext(Dispatchers.IO) {
                                    received.uri?.let { AttachmentUtils.uriToChatAttachment(this@MainActivity, it) }
                                        ?: received.bitmap?.let { AttachmentUtils.bitmapToChatAttachment(it) }
                                }
                                if (attachment == null) {
                                    evidenceError = "This evidence could not be read or exceeds the 5 MiB limit."
                                    processed = true
                                }
                                else if (received.target.fingerprint != PairingConfigParser.destinationIdentity(viewModel.nodeConfig.value) ||
                                    received.target.demo != viewModel.nodeConfig.value.simulatedNodeEnabled) {
                                    evidenceError = "Desktop pairing changed while opening the evidence. Select it again for the current workspace."
                                    processed = true
                                }
                                else if (viewModel.vaultReady.value && EncryptionManager.isUnlocked.value) {
                                    viewModel.acceptPendingEvidence(received.target.route, attachment)
                                    if (received.target.route.startsWith("chat:"))
                                        viewModel.selectSession(received.target.route.removePrefix("chat:"))
                                    else currentTab = if (received.target.route == "vault") ScreenTab.FILES else ScreenTab.CAPTURE
                                    processed = true
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            evidenceError = "The evidence could not be opened safely. Select it again."
                            processed = true
                        } finally {
                            if (processed) { pendingExternal = null; received.bitmap?.recycle() }
                        }
                    }
                }
                LaunchedEffect(evidenceError, isUnlocked) {
                    if (isUnlocked) evidenceError?.let {
                        snackbarHostState.showSnackbar(it); evidenceError = null
                    }
                }

                // Secure Clinical Mode enforcement (FLAG_SECURE blocks screenshots and recents preview)
                LaunchedEffect(secureClinicalMode, isUnlocked) {
                    if (secureClinicalMode || !isUnlocked) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                LaunchedEffect(isUnlocked) {
                    if (isUnlocked) {
                        delay(300_000L)
                        viewModel.lockVault()
                    }
                }

                LaunchedEffect(feedbackMessage) {
                    feedbackMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearFeedback()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!isUnlocked) {
                        BiometricLockOverlay(onTriggerBiometrics = { authenticate() }, error = authenticationError)
                    } else if (!vaultReady) {
                        Column(Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(vaultError ?: "Opening the protected workspace… Existing records will be preserved.")
                            androidx.compose.material3.Button(onClick = { viewModel.lockVault() }) {
                                Text("Lock and retry")
                            }
                        }
                    } else if (activeSessionId != null) {
                        // Detailed Chat Screen with Voice, Streaming, Attachments, Model Indicator
                        ChatDetailScreen(
                            session = activeSession,
                            messages = activeSessionMessages,
                            connectionStatus = connectionStatus,
                            hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                            modelMode = modelMode,
                            isRequestRunning = isStreamingResponse,
                            onModelModeChange = { viewModel.setModelProcessingMode(it) },
                            decryptText = { viewModel.decrypt(it) },
                            decryptAttachmentBitmap = { viewModel.decryptAttachmentBitmap(it) },
                            onSendMessage = { prompt, attachment -> viewModel.sendMessage(prompt, attachment) },
                            onStopStreaming = { viewModel.stopStreaming() },
                            onBackClick = { viewModel.selectSession(null) },
                            onPickEvidence = { pickEvidence(chatRoute, it) },
                            onTakePhoto = { takePhoto(chatRoute) },
                            incomingEvidence = pendingEvidence?.takeIf { it.route == chatRoute }?.attachment,
                            onConsumeEvidence = viewModel::clearPendingEvidence,
                            loadUnsentDraft = { viewModel.restoreUnsentDraft(chatRoute) },
                            onUnsentDraftChange = { viewModel.saveUnsentDraft(chatRoute, it) }
                        )
                    } else {
                        // Main Scaffold with 5 Core Navigation Sections (Home, Chat, Tasks, Alerts, Files)
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            floatingActionButton = {
                                if (currentTab != ScreenTab.CAPTURE) {
                                    ExtendedFloatingActionButton(onClick = {
                                        currentTab = ScreenTab.CAPTURE
                                        viewModel.refreshWorkflows()
                                    }, modifier = Modifier.testTag("notes_followups_button"),
                                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                        text = { Text("Notes & follow-ups") })
                                }
                            },
                            bottomBar = {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = androidx.compose.ui.unit.Dp(8f)
                                ) {
                                    // 1. Home (Executive Dashboard)
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.HOME,
                                        onClick = { currentTab = ScreenTab.HOME },
                                        icon = {
                                            Icon(Icons.Default.Dashboard, contentDescription = "Home")
                                        },
                                        label = { Text("Home") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_home")
                                    )

                                    // 2. Chat
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.CHAT,
                                        onClick = { currentTab = ScreenTab.CHAT },
                                        icon = {
                                            BadgedBox(badge = {
                                                if (chatSessions.isNotEmpty()) {
                                                    Badge { Text("${chatSessions.size}") }
                                                }
                                            }) {
                                                Icon(Icons.Default.Chat, contentDescription = "Chat")
                                            }
                                        },
                                        label = { Text("Chat") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_chat")
                                    )

                                    // 3. Tasks
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.TASKS,
                                        onClick = { currentTab = ScreenTab.TASKS },
                                        icon = {
                                            val runningCount = tasks.count { it.status == "RUNNING" }
                                            BadgedBox(badge = {
                                                if (runningCount > 0) {
                                                    Badge { Text("$runningCount") }
                                                }
                                            }) {
                                                Icon(Icons.Default.SmartToy, contentDescription = "Tasks")
                                            }
                                        },
                                        label = { Text("Tasks") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_tasks")
                                    )

                                    // 4. Alerts
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.ALERTS,
                                        onClick = { currentTab = ScreenTab.ALERTS },
                                        icon = {
                                            val unacknowledgedCount = notifications.count { !it.isAcknowledged }
                                            BadgedBox(badge = {
                                                if (unacknowledgedCount > 0) {
                                                    Badge(containerColor = StatusRed) { Text("$unacknowledgedCount") }
                                                }
                                            }) {
                                                Icon(Icons.Default.NotificationsActive, contentDescription = "Alerts")
                                            }
                                        },
                                        label = { Text("Alerts") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_alerts")
                                    )

                                    // 5. Files
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.FILES,
                                        onClick = { currentTab = ScreenTab.FILES },
                                        icon = {
                                            Icon(Icons.Default.FolderShared, contentDescription = "Files")
                                        },
                                        label = { Text("Files") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_files")
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                when (currentTab) {
                                    ScreenTab.HOME -> DashboardScreen(
                                        connectionStatus = connectionStatus,
                                        lastSyncTimestamp = lastSyncTimestamp,
                                        isSyncing = isSyncing,
                                        batteryMode = nodeConfig.batteryMode,
                                        nodeStats = nodeStats,
                                        tasks = tasks,
                                        notifications = notifications,
                                        onSyncClick = { viewModel.triggerSync() },
                                        onNavigateChats = { currentTab = ScreenTab.CHAT },
                                        onNavigateVault = { currentTab = ScreenTab.FILES },
                                        onNavigateTasks = { currentTab = ScreenTab.TASKS },
                                        onNavigateAlerts = { currentTab = ScreenTab.ALERTS },
                                        onNavigateSettings = { currentTab = ScreenTab.SETTINGS },
                                        onLockClick = { viewModel.lockVault() },
                                        onNotificationClick = { viewModel.markNotificationRead(it) },
                                        onQuickAction = { actionName ->
                                            when (actionName) {
                                                "Morning Administrator Brief" -> viewModel.startAgent(actionName)
                                                "Daily Clinical Surveillance" -> viewModel.runPlaybook(actionName)
                                                "Antipsychotic Review" -> viewModel.runPlaybook(actionName)
                                                else -> viewModel.startAgent(actionName)
                                            }
                                        }
                                    )

                                    ScreenTab.CHAT -> ChatListScreen(
                                        sessions = chatSessions,
                                        decryptText = { viewModel.decrypt(it) },
                                        onSessionClick = { viewModel.selectSession(it) },
                                        onCreateNewSession = { viewModel.createNewChat(it) },
                                        onDeleteSession = { viewModel.deleteSession(it) }
                                    )

                                    ScreenTab.TASKS -> TasksScreen(
                                        tasks = tasks,
                                        connectionStatus = connectionStatus,
                                        hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                                        onStartAgent = { viewModel.startAgent(it) },
                                        onRunPlaybook = { viewModel.runPlaybook(it) },
                                        onCancelTask = { viewModel.cancelTask(it) },
                                        onRetryTask = { viewModel.retryTask(it) },
                                        onSubmitApproval = { id, approved -> viewModel.submitApproval(id, approved) },
                                        onRefreshTasks = { viewModel.triggerSync() }
                                    )

                                    ScreenTab.ALERTS -> AlertsScreen(
                                        alerts = notifications,
                                        connectionStatus = connectionStatus,
                                        hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                                        onAcknowledgeAlert = { viewModel.acknowledgeAlert(it) },
                                        onApproveAction = { id, approved -> viewModel.submitApproval(id, approved) },
                                        onRunAnalysis = { analysisName -> viewModel.runPlaybook(analysisName) },
                                        onMarkAllRead = { viewModel.markAllNotificationsRead() }
                                    )

                                    ScreenTab.FILES -> DocumentVaultScreen(
                                        documents = documents,
                                        lastSavedCaptureId = lastSavedCaptureId,
                                        decryptText = { viewModel.decrypt(it) },
                                        onSaveDocument = { name, type, content, tags ->
                                            viewModel.saveDocument(name, type, content, tags)
                                        },
                                        onDeleteDocument = { viewModel.deleteDocument(it) },
                                        onSaveEvidence = viewModel::captureEvidence,
                                        onPickEvidence = { pickEvidence("vault", it) },
                                        onTakePhoto = { takePhoto("vault") },
                                        incomingEvidence = pendingEvidence?.takeIf { it.route == "vault" }?.attachment,
                                        onConsumeEvidence = viewModel::clearPendingEvidence,
                                        loadUnsentDraft = { viewModel.restoreUnsentDraft("vault") },
                                        onUnsentDraftChange = { viewModel.saveUnsentDraft("vault", it) },
                                        onSendDocToChat = { title, content ->
                                            viewModel.createNewChat("Doc: $title", "Analyze this report:\n\n$content")
                                        }
                                    )

                                    ScreenTab.SETTINGS -> NodeSettingsScreen(
                                        currentConfig = nodeConfig,
                                        connectionStatus = connectionStatus,
                                        secureClinicalMode = secureClinicalMode,
                                        onSaveConfig = {
                                            viewModel.updateNodeConfig(it)
                                            if (it.backgroundNotificationsEnabled) requestNotificationPermission()
                                        },
                                        onToggleSecureClinicalMode = { viewModel.setSecureClinicalMode(it) },
                                        onTestConnection = { viewModel.triggerSync() },
                                        onRequestNotificationPermission = { requestNotificationPermission() },
                                        onLockVault = { viewModel.lockVault() }
                                    )

                                    ScreenTab.CAPTURE -> FacilityCaptureScreen(
                                        snapshot = workflowState,
                                        lastSavedCaptureId = lastSavedCaptureId,
                                        busy = workflowBusy,
                                        error = vaultError,
                                        onCapture = viewModel::createCapture,
                                        onRefresh = viewModel::refreshWorkflows,
                                        onUpdateAction = viewModel::updateWorkflowAction,
                                        onSaveDraft = viewModel::saveWorkflowDraft,
                                        onAcknowledge = viewModel::acknowledgeFollowUp,
                                        onBack = { currentTab = ScreenTab.HOME },
                                        onPickEvidence = { pickEvidence("capture", it) },
                                        onTakePhoto = { takePhoto("capture") },
                                        incomingEvidence = pendingEvidence?.takeIf { it.route == "capture" }?.attachment,
                                        onConsumeEvidence = viewModel::clearPendingEvidence,
                                        loadUnsentDraft = { viewModel.restoreUnsentDraft("capture") },
                                        onUnsentDraftChange = { viewModel.saveUnsentDraft("capture", it) }
                                    )
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}
