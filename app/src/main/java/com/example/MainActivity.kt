package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
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
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusRed

enum class ScreenTab(val title: String, val testTag: String) {
    HOME("Home", "nav_home"),
    CHAT("Chat", "nav_chat"),
    TASKS("Tasks", "nav_tasks"),
    ALERTS("Alerts", "nav_alerts"),
    FILES("Files", "nav_files"),
    SETTINGS("Settings", "nav_settings")
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
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

                val snackbarHostState = remember { SnackbarHostState() }
                var currentTab by remember { mutableStateOf(ScreenTab.HOME) }

                // Secure Clinical Mode enforcement (FLAG_SECURE blocks screenshots and recents preview)
                LaunchedEffect(secureClinicalMode) {
                    if (secureClinicalMode) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                LaunchedEffect(feedbackMessage) {
                    feedbackMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearFeedback()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (activeSessionId != null) {
                        // Detailed Chat Screen with Voice, Streaming, Attachments, Model Indicator
                        ChatDetailScreen(
                            session = activeSession,
                            messages = activeSessionMessages,
                            connectionStatus = connectionStatus,
                            hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                            modelMode = modelMode,
                            onModelModeChange = { viewModel.setModelProcessingMode(it) },
                            decryptText = { viewModel.decrypt(it) },
                            decryptAttachmentBitmap = { viewModel.decryptAttachmentBitmap(it) },
                            onSendMessage = { prompt, attachment -> viewModel.sendMessage(prompt, attachment) },
                            onStopStreaming = { viewModel.stopStreaming() },
                            onBackClick = { viewModel.selectSession(null) }
                        )
                    } else {
                        // Main Scaffold with 5 Core Navigation Sections (Home, Chat, Tasks, Alerts, Files)
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                        decryptText = { viewModel.decrypt(it) },
                                        onSaveDocument = { name, type, content, tags ->
                                            viewModel.saveDocument(name, type, content, tags)
                                        },
                                        onDeleteDocument = { viewModel.deleteDocument(it) },
                                        onSendDocToChat = { title, content ->
                                            viewModel.createNewChat("Doc: $title", "Analyze this report:\n\n$content")
                                        }
                                    )

                                    ScreenTab.SETTINGS -> NodeSettingsScreen(
                                        currentConfig = nodeConfig,
                                        connectionStatus = connectionStatus,
                                        secureClinicalMode = secureClinicalMode,
                                        onSaveConfig = { viewModel.updateNodeConfig(it) },
                                        onToggleSecureClinicalMode = { viewModel.setSecureClinicalMode(it) },
                                        onTestConnection = { viewModel.triggerSync() },
                                        onLockVault = { viewModel.lockVault() }
                                    )
                                }
                            }
                        }
                    }

                    // Biometric Lock Gate (Hardware Keystore & Biometric authorization)
                    if (!isUnlocked) {
                        BiometricLockOverlay(
                            onTriggerBiometrics = {
                                viewModel.biometricAuthManager.promptBiometricAuth(
                                    activity = this@MainActivity,
                                    onSuccess = { /* StateFlow updates automatically */ },
                                    onError = { /* PIN fallback remains active */ }
                                )
                            },
                            onPinSubmit = { pin ->
                                viewModel.unlockWithPin(pin)
                            }
                        )
                    }
                }
            }
        }
    }
}
