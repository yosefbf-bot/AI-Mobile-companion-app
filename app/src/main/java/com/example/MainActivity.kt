package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
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
import com.example.ui.screens.chats.ChatDetailScreen
import com.example.ui.screens.chats.ChatListScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.settings.NodeSettingsScreen
import com.example.ui.screens.tasks.TasksScreen
import com.example.ui.screens.vault.DocumentVaultScreen
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.MyApplicationTheme

enum class ScreenTab(val title: String, val testTag: String) {
    DASHBOARD("Dashboard", "nav_dashboard"),
    CHATS("Chats", "nav_chats"),
    VAULT("Vault", "nav_vault"),
    TASKS("Tasks", "nav_tasks"),
    SETTINGS("Node", "nav_settings")
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

                val snackbarHostState = remember { SnackbarHostState() }
                var currentTab by remember { mutableStateOf(ScreenTab.DASHBOARD) }

                LaunchedEffect(feedbackMessage) {
                    feedbackMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearFeedback()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (activeSessionId != null) {
                        // Detailed Chat Screen
                        ChatDetailScreen(
                            session = activeSession,
                            messages = activeSessionMessages,
                            connectionStatus = connectionStatus,
                            hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                            decryptText = { viewModel.decrypt(it) },
                            decryptAttachmentBitmap = { viewModel.decryptAttachmentBitmap(it) },
                            onSendMessage = { prompt, attachment -> viewModel.sendMessage(prompt, attachment) },
                            onBackClick = { viewModel.selectSession(null) }
                        )
                    } else {
                        // Main Scaffold with Bottom Navigation
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = androidx.compose.ui.unit.Dp(8f)
                                ) {
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.DASHBOARD,
                                        onClick = { currentTab = ScreenTab.DASHBOARD },
                                        icon = {
                                            Icon(Icons.Default.Dashboard, contentDescription = "Dashboard")
                                        },
                                        label = { Text("Dashboard") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_dashboard")
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.CHATS,
                                        onClick = { currentTab = ScreenTab.CHATS },
                                        icon = {
                                            BadgedBox(badge = {
                                                if (chatSessions.isNotEmpty()) {
                                                    Badge { Text("${chatSessions.size}") }
                                                }
                                            }) {
                                                Icon(Icons.Default.Chat, contentDescription = "Chats")
                                            }
                                        },
                                        label = { Text("Chats") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_chats")
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.VAULT,
                                        onClick = { currentTab = ScreenTab.VAULT },
                                        icon = {
                                            Icon(Icons.Default.FolderShared, contentDescription = "Vault")
                                        },
                                        label = { Text("Vault") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_vault")
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.TASKS,
                                        onClick = { currentTab = ScreenTab.TASKS },
                                        icon = {
                                            val unreadCount = notifications.count { !it.isRead }
                                            BadgedBox(badge = {
                                                if (unreadCount > 0) {
                                                    Badge { Text("$unreadCount") }
                                                }
                                            }) {
                                                Icon(Icons.Default.ListAlt, contentDescription = "Tasks")
                                            }
                                        },
                                        label = { Text("Tasks") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_tasks")
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.SETTINGS,
                                        onClick = { currentTab = ScreenTab.SETTINGS },
                                        icon = {
                                            Icon(Icons.Default.Settings, contentDescription = "Node")
                                        },
                                        label = { Text("Node") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberTeal,
                                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_settings")
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                when (currentTab) {
                                    ScreenTab.DASHBOARD -> DashboardScreen(
                                        connectionStatus = connectionStatus,
                                        lastSyncTimestamp = lastSyncTimestamp,
                                        isSyncing = isSyncing,
                                        batteryMode = nodeConfig.batteryMode,
                                        nodeStats = nodeStats,
                                        tasks = tasks,
                                        notifications = notifications,
                                        onSyncClick = { viewModel.triggerSync() },
                                        onNavigateChats = { currentTab = ScreenTab.CHATS },
                                        onNavigateVault = { currentTab = ScreenTab.VAULT },
                                        onNavigateTasks = { currentTab = ScreenTab.TASKS },
                                        onNavigateSettings = { currentTab = ScreenTab.SETTINGS },
                                        onLockClick = { viewModel.lockVault() },
                                        onNotificationClick = { viewModel.markNotificationRead(it) }
                                    )

                                    ScreenTab.CHATS -> ChatListScreen(
                                        sessions = chatSessions,
                                        decryptText = { viewModel.decrypt(it) },
                                        onSessionClick = { viewModel.selectSession(it) },
                                        onCreateNewSession = { viewModel.createNewChat(it) },
                                        onDeleteSession = { viewModel.deleteSession(it) }
                                    )

                                    ScreenTab.VAULT -> DocumentVaultScreen(
                                        documents = documents,
                                        decryptText = { viewModel.decrypt(it) },
                                        onSaveDocument = { name, type, content, tags ->
                                            viewModel.saveDocument(name, type, content, tags)
                                        },
                                        onDeleteDocument = { viewModel.deleteDocument(it) }
                                    )

                                    ScreenTab.TASKS -> TasksScreen(
                                        tasks = tasks,
                                        notifications = notifications,
                                        onNotificationClick = { viewModel.markNotificationRead(it) },
                                        onMarkAllNotificationsRead = { viewModel.markAllNotificationsRead() },
                                        onRefreshTasks = { viewModel.triggerSync() }
                                    )

                                    ScreenTab.SETTINGS -> NodeSettingsScreen(
                                        currentConfig = nodeConfig,
                                        connectionStatus = connectionStatus,
                                        onSaveConfig = { viewModel.updateNodeConfig(it) },
                                        onTestConnection = { viewModel.triggerSync() },
                                        onLockVault = { viewModel.lockVault() }
                                    )
                                }
                            }
                        }
                    }

                    // Biometric Lock Gate (Prevents unauthorized local access)
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
