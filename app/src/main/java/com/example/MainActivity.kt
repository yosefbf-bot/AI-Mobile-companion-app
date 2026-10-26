package com.example

import android.os.Bundle
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.ui.theme.AppearancePreferences
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import com.example.ui.ScreenCapturePreferences
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ui.AuthenticationCompletion
import com.example.ui.components.LocalDraftForm
import com.example.data.crypto.DeviceScreenLock
import com.example.data.crypto.DeviceScreenLockState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.example.ui.MainViewModel
import com.example.ui.UnlockPromptState
import com.example.data.crypto.BiometricStatus
import com.example.ui.components.BiometricLockOverlay
import com.example.ui.screens.alerts.AlertsScreen
import com.example.ui.screens.chats.ChatDetailScreen
import com.example.ui.screens.chats.ChatListScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.settings.NodeSettingsScreen
import com.example.ui.pairing.QrPairingScannerScreen
import com.example.ui.screens.tasks.TasksScreen
import com.example.ui.screens.vault.DocumentVaultScreen
import com.example.ui.screens.workflows.FacilityCaptureScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
    CAPTURE("Notes & follow-ups", "nav_capture"),
    MEETINGS("Meetings", "nav_meetings")
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val unlockPromptState: UnlockPromptState by viewModels()
    private var biometricPrompt: BiometricPrompt? = null
    private var authenticationCompletion: AuthenticationCompletion? = null
    private var authenticationCompletionJob: Job? = null
    private var sessionChangeJob: Job? = null
    private var sessionChangeError by mutableStateOf<String?>(null)
    private var screenLockState by mutableStateOf(DeviceScreenLockState.UNAVAILABLE)
    private var resumedForAuthentication by mutableStateOf(false)
    private val screenCapturePreferences by lazy { ScreenCapturePreferences(this) }
    private var allowScreenshots by mutableStateOf(false)
    private var notificationRequestInProgress = false
    private var scanningPairing by mutableStateOf(false)
    private data class ExternalTarget(val route: String, val fingerprint: String, val demo: Boolean)
    private data class ExternalResult(val target: ExternalTarget, val uri: Uri? = null, val bitmap: Bitmap? = null)
    private var externalTarget: ExternalTarget? = null
    private var pendingExternal by mutableStateOf<ExternalResult?>(null)
    private var evidenceError by mutableStateOf<String?>(null)
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        externalTarget?.let { target -> if (uri != null) pendingExternal = ExternalResult(target, uri = uri) }
        externalTarget = null
        authenticateWhenReady()
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        externalTarget?.let { target -> if (bitmap != null) pendingExternal = ExternalResult(target, bitmap = bitmap) }
        externalTarget = null
        authenticateWhenReady()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && externalTarget != null) camera.launch(null)
        else { externalTarget = null; evidenceError = "Camera access was not granted."; authenticateWhenReady() }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationRequestInProgress = false
        if (granted) FollowUpNotifications.configure(this, viewModel.nodeConfig.value.backgroundNotificationsEnabled)
        else evidenceError = "Notification permission is off. Follow-ups remain pending on the desktop."
        authenticateWhenReady()
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
        if (!notificationRequestInProgress && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequestInProgress = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private val deviceCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onDeviceAuthenticated()
        else unlockPromptState.onFailure("Device authentication did not complete. Try again when ready.", terminal = true)
    }

    private fun authenticate() {
        if (unlockPromptState.beginRetry(!viewModel.isUnlocked.value, authenticationForegroundReady()))
            launchAuthentication()
    }

    private fun authenticationForegroundReady(): Boolean = resumedForAuthentication &&
        window.decorView.hasWindowFocus() && !supportFragmentManager.isStateSaved &&
        externalTarget == null && !notificationRequestInProgress

    private fun onDeviceAuthenticated() {
        if (!unlockPromptState.promptInProgress) return
        viewModel.biometricAuthManager.recordDeviceAuthentication()
        unlockPromptState.onAuthenticated()
        authenticateWhenReady()
    }

    private fun authenticateWhenReady() {
        EncryptionManager.hasCurrentAccess()
        val ready = authenticationForegroundReady()
        val completion = unlockPromptState.beginCompletion(ready)
        if (completion != null) {
            authenticationCompletion = completion
            val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val granted = viewModel.biometricAuthManager.completeDeviceCredentialAuthentication(
                        completion.requestedSessionMinutes,
                        beforeGrant = {
                            authenticationForegroundReady() && DeviceScreenLock.read(this@MainActivity) == DeviceScreenLockState.SECURE_UNLOCKED &&
                                authenticationCompletion == completion && unlockPromptState.isCurrentCompletion(completion)
                        })
                    if (granted) unlockPromptState.finishCompletion(completion)
                    else if (!authenticationForegroundReady()) unlockPromptState.deferCompletion(completion)
                    else unlockPromptState.failCompletion(completion,
                        "The protected workspace could not be unlocked. Existing data and drafts were preserved.")
                } catch (canceled: CancellationException) {
                    unlockPromptState.deferCompletion(completion)
                    throw canceled
                } catch (_: Exception) {
                    unlockPromptState.failCompletion(completion,
                        "The protected workspace could not be unlocked. Existing data and drafts were preserved.")
                } finally {
                    unlockPromptState.deferCompletion(completion)
                    if (authenticationCompletion == completion) {
                        authenticationCompletion = null
                        authenticationCompletionJob = null
                    }
                }
            }
            authenticationCompletionJob = job
            job.start()
        } else if (unlockPromptState.beginAutomatic(!viewModel.isUnlocked.value, ready)) {
            launchAuthentication()
        }
    }

    private fun launchAuthentication() {
        val auth = viewModel.biometricAuthManager
        screenLockState = DeviceScreenLock.read(this)
        if (screenLockState != DeviceScreenLockState.SECURE_UNLOCKED || auth.checkBiometricAvailability() != BiometricStatus.AVAILABLE) {
            unlockPromptState.onFailure("Set up this phone's Android screen lock in device security settings, then try again.", terminal = true)
            return
        }
        try {
            if (auth.requiresLegacyDeviceCredential) {
                val intent = auth.deviceCredentialIntent()
                if (intent == null) unlockPromptState.onFailure("Android device authentication is unavailable. Check device security settings.", terminal = true)
                else deviceCredential.launch(intent)
            } else {
                val prompt = bindAuthenticationPrompt()
                biometricPrompt = prompt
                auth.promptBiometricAuth(prompt)
            }
        } catch (_: IllegalStateException) {
            unlockPromptState.onFailure("Device authentication could not open safely. Try again.", terminal = true)
        } catch (_: android.content.ActivityNotFoundException) {
            unlockPromptState.onFailure("Android device authentication is unavailable. Check device security settings.", terminal = true)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        resumedForAuthentication = true
        screenLockState = DeviceScreenLock.read(this)
        authenticateWhenReady()
        applyScreenCapturePolicy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumedForAuthentication) {
            screenLockState = DeviceScreenLock.read(this)
            authenticateWhenReady()
        }
    }

    override fun onPause() {
        viewModel.draftController.captureBeforeLock()
        resumedForAuthentication = false
        // Protect the app before Android can snapshot its background transition.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        deferAuthenticationCompletion()
        sessionChangeJob?.cancel()
        sessionChangeJob = null
        super.onPause()
    }

    private fun deferAuthenticationCompletion() {
        authenticationCompletion?.let(unlockPromptState::deferCompletion)
        authenticationCompletion = null
        authenticationCompletionJob?.cancel()
        authenticationCompletionJob = null
    }

    override fun onStop() {
        deferAuthenticationCompletion()
        lockProtectedWorkspace()
        unlockPromptState.onStopped(isChangingConfigurations)
        super.onStop()
    }

    private fun applyScreenCapturePolicy() = synchronized(EncryptionManager) {
        // Read live authorization/foreground state, never a delayed Compose snapshot.
        val currentAccess = EncryptionManager.hasCurrentAccess()
        val ready = viewModel.vaultReady.value
        if (allowScreenshots && resumedForAuthentication && currentAccess && ready)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun updateAllowScreenshots(allowed: Boolean) = synchronized(EncryptionManager) {
        // Expiry/lock cannot interleave between this check and the nonsecret preference write.
        if (!resumedForAuthentication || !EncryptionManager.hasCurrentAccess() || !viewModel.vaultReady.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return@synchronized
        }
        screenCapturePreferences.saveAllowed(allowed)
        allowScreenshots = allowed
        applyScreenCapturePolicy()
    }

    private fun requestSessionChange(minutes: Int) {
        screenLockState = DeviceScreenLock.read(this)
        if (minutes !in setOf(5, 10, 20, 30) || minutes == EncryptionManager.committedSessionMinutes.value ||
            sessionChangeJob != null || !authenticationForegroundReady() ||
            screenLockState != DeviceScreenLockState.SECURE_UNLOCKED || !EncryptionManager.hasCurrentAccess() || !viewModel.vaultReady.value) return
        val generation = EncryptionManager.accessGeneration
        sessionChangeError = null
        if (!viewModel.draftController.captureBeforeLock()) {
            sessionChangeError = "The latest draft could not be protected. Keep this app open and retry before changing the unlock period."
            return
        }
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!viewModel.draftController.flush()) {
                    sessionChangeError = "Drafts could not be saved. Keep this app open and retry before changing the unlock period."
                    return@launch
                }
                if (!authenticationForegroundReady() || DeviceScreenLock.read(this@MainActivity) != DeviceScreenLockState.SECURE_UNLOCKED ||
                    generation != EncryptionManager.accessGeneration || !viewModel.vaultReady.value ||
                    !unlockPromptState.prepareSessionChange(minutes)) return@launch
                // No discard: both durable drafts and encrypted RAM fallback participate in migration.
                lockProtectedWorkspace()
                authenticate()
            } finally { if (sessionChangeJob === coroutineContext[Job]) sessionChangeJob = null }
        }
        sessionChangeJob = job
        job.start()
    }

    private fun lockProtectedWorkspace() {
        viewModel.draftController.captureBeforeLock()
        scanningPairing = false
        // Protect locked recents synchronously; Compose collection can pause at Stop.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.lockVault()
    }

    private fun bindAuthenticationPrompt(): BiometricPrompt = viewModel.biometricAuthManager.createBiometricPrompt(this,
            onAuthenticated = ::onDeviceAuthenticated,
            onError = {
                // AndroidX rebinds the callback on recreation. An abandoned prompt must not lock a newer grant.
                if (unlockPromptState.promptInProgress && !viewModel.isUnlocked.value) {
                    deferAuthenticationCompletion()
                    viewModel.biometricAuthManager.lock()
                    unlockPromptState.onFailure(it, terminal = true)
                }
            },
            onFailed = { unlockPromptState.onFailure(it, terminal = false) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        allowScreenshots = screenCapturePreferences.readAllowed()
        biometricPrompt = bindAuthenticationPrompt()
        enableEdgeToEdge()

        setContent {
            val appearancePreferences = remember { AppearancePreferences(this@MainActivity) }
            var appearance by remember { mutableStateOf(appearancePreferences.read()) }
            MyApplicationTheme(darkTheme = appearance.isDark(isSystemInDarkTheme())) {
                val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
                val vaultReady by viewModel.vaultReady.collectAsStateWithLifecycle()
                val vaultError by viewModel.lastError.collectAsStateWithLifecycle()
                val workflowState by viewModel.workflowState.collectAsStateWithLifecycle()
                val workflowBusy by viewModel.workflowBusy.collectAsStateWithLifecycle()
                val lastSavedCaptureId by viewModel.lastSavedCaptureId.collectAsStateWithLifecycle()
                val pendingEvidence by viewModel.pendingEvidence.collectAsStateWithLifecycle()
                val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
                val lastConnectionCheck by viewModel.lastConnectionCheck.collectAsStateWithLifecycle()
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
                val alertCoverage by viewModel.alertCoverage.collectAsStateWithLifecycle()
                val feedbackMessage by viewModel.userFeedback.collectAsStateWithLifecycle()
                val secureClinicalMode by viewModel.secureClinicalMode.collectAsStateWithLifecycle()
                val sessionMinutes by EncryptionManager.committedSessionMinutes.collectAsStateWithLifecycle()
                val sessionPolicyError by EncryptionManager.sessionPolicyChangeError.collectAsStateWithLifecycle()
                val autoSaveDrafts by viewModel.draftController.autoSave.collectAsStateWithLifecycle()
                val documentSaved by viewModel.documentSaved.collectAsStateWithLifecycle()
                val modelMode by viewModel.modelProcessingMode.collectAsStateWithLifecycle()
                val isStreamingResponse by viewModel.isStreamingResponse.collectAsStateWithLifecycle()
                val lastSavedChatSubmission by viewModel.lastSavedChatSubmission.collectAsStateWithLifecycle()
                val chatSubmissionFinished by viewModel.chatSubmissionFinished.collectAsStateWithLifecycle()
                val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()

                val snackbarHostState = remember { SnackbarHostState() }
                var currentTab by remember { mutableStateOf(ScreenTab.CHAT) }
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
                                    evidenceError = "This evidence could not be read, is unsupported or exceeds 5 MiB. " + com.example.data.crypto.EvidenceValidator.SUPPORTED_FORMATS
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

                // Apply during the UI commit, before drawing the lock/opening surface.
                // Disposal and lifecycle callbacks fail closed even if collection is paused.
                DisposableEffect(isUnlocked, vaultReady, allowScreenshots, resumedForAuthentication) {
                    applyScreenCapturePolicy()
                    onDispose { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }

                LaunchedEffect(isUnlocked) { if (!isUnlocked) scanningPairing = false }
                LaunchedEffect(currentTab) { if (currentTab != ScreenTab.SETTINGS) viewModel.cancelPairing() }

                LaunchedEffect(feedbackMessage, isUnlocked, vaultReady) {
                    if (isUnlocked && vaultReady) feedbackMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearFeedback()
                    }
                    else snackbarHostState.currentSnackbarData?.dismiss()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!isUnlocked) {
                        BiometricLockOverlay(onTriggerBiometrics = { authenticate() }, error = unlockPromptState.error,
                            authenticationInProgress = unlockPromptState.promptInProgress,
                            onOpenSecuritySettings = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
                    } else if (!vaultReady) {
                        Column(Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(vaultError ?: "Opening the protected workspace… Existing records will be preserved.")
                            androidx.compose.material3.Button(onClick = { lockProtectedWorkspace() }) {
                                Text("Lock and retry")
                            }
                        }
                    } else if (scanningPairing) {
                        QrPairingScannerScreen(onDecoded = {
                            scanningPairing = false; currentTab = ScreenTab.SETTINGS; viewModel.beginPairing(it)
                        }, onCancel = { scanningPairing = false; currentTab = ScreenTab.SETTINGS },
                            onResourcesChanged = { viewModel.stopPairingScanner = it })
                    } else if (activeSessionId != null) {
                        // Detailed Chat Screen with Voice, Streaming, Attachments, Model Indicator
                        LocalDraftForm(viewModel.draftController, chatRoute) { draft ->
                        ChatDetailScreen(
                            session = activeSession,
                            messages = activeSessionMessages,
                            connectionStatus = connectionStatus,
                            hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                            modelMode = modelMode,
                            isRequestRunning = isStreamingResponse,
                            lastSavedSubmission = lastSavedChatSubmission,
                            submissionFinished = chatSubmissionFinished,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            lastConnectionCheck = lastConnectionCheck,
                            isSyncing = isSyncing,
                            onCheckConnection = { viewModel.triggerSync() },
                            onAskAgain = { viewModel.askAgain(it.id) },
                            onModelModeChange = { viewModel.setModelProcessingMode(it) },
                            decryptText = { viewModel.decrypt(it) },
                            loadDisplayText = viewModel::loadDisplayText,
                            decryptAttachmentBitmap = { viewModel.decryptAttachmentBitmap(it) },
                            onSendMessage = { prompt, attachment -> viewModel.sendMessage(prompt, attachment) },
                            onStopStreaming = { viewModel.stopStreaming() },
                            onBackClick = { viewModel.selectSession(null) },
                            onPickEvidence = { pickEvidence(chatRoute, it) },
                            onTakePhoto = { takePhoto(chatRoute) },
                            incomingEvidence = pendingEvidence?.takeIf { it.route == chatRoute }?.attachment,
                            onConsumeEvidence = viewModel::clearPendingEvidence,
                            loadUnsentDraft = { draft.form }, draftState = draft,
                            onDraftFieldsChange = draft::update
                        )
                        }
                    } else {
                        // Main Scaffold with 5 Core Navigation Sections (Home, Chat, Tasks, Alerts, Files)
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("desktop_sync_status")) {
                                    com.example.ui.components.ConnectionStatusBadge(status = if(isSyncing) com.example.data.model.ConnectionStatus.SYNCING else connectionStatus, hostIp = nodeConfig.hostIp, lastCheckedAt = lastConnectionCheck, onClick = { viewModel.triggerSync() })
                                    Text(if(nodeConfig.simulatedNodeEnabled) "Demo data · no desktop sync" else if(lastSyncTimestamp > 0) "Last synced: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastSyncTimestamp))}" else "Not synced yet · tap status to connect", style = MaterialTheme.typography.labelSmall)
                                    if (!nodeConfig.isPaired && !nodeConfig.simulatedNodeEnabled) {
                                        Text("Connect to Private AI on your PC", style = MaterialTheme.typography.titleSmall)
                                        Row {
                                            androidx.compose.material3.TextButton(onClick = { scanningPairing = true }, modifier = Modifier.testTag("onboarding_scan_qr")) { Text("Scan desktop QR") }
                                            androidx.compose.material3.TextButton(onClick = { currentTab = ScreenTab.SETTINGS }) { Text("Manual pairing") }
                                        }
                                    } else androidx.compose.material3.TextButton(onClick = { currentTab = ScreenTab.SETTINGS }) { Text("Connection settings") }
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
                                        onClick = { currentTab = ScreenTab.HOME; viewModel.refreshWorkflows() },
                                        icon = {
                                            Icon(Icons.Default.Dashboard, contentDescription = "Home")
                                        },
                                        label = { Text("Dashboard") },
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

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.CAPTURE,
                                        onClick = { currentTab = ScreenTab.CAPTURE; viewModel.refreshWorkflows() },
                                        icon = { Icon(Icons.Default.Add, contentDescription = "Notes") },
                                        label = { Text("Notes") },
                                        modifier = Modifier.testTag("notes_followups_button")
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.MEETINGS,
                                        onClick = { currentTab = ScreenTab.MEETINGS },
                                        icon = { Icon(Icons.Default.SmartToy, contentDescription = "Meetings") },
                                        label = { Text("Meetings") },
                                        modifier = Modifier.testTag("nav_meetings")
                                    )
                                    // 5. Files
                                    NavigationBarItem(
                                        selected = currentTab == ScreenTab.FILES,
                                        onClick = { currentTab = ScreenTab.FILES },
                                        icon = {
                                            Icon(Icons.Default.FolderShared, contentDescription = "Files")
                                        },
                                        label = { Text("Library") },
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
                                    ScreenTab.MEETINGS -> com.example.ui.screens.meetings.MeetingsScreen(viewModel)
                                    ScreenTab.HOME -> DashboardScreen(
                                        workflows = workflowState,
                                        onNavigateNotes = { currentTab = ScreenTab.CAPTURE; viewModel.refreshWorkflows() },
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
                                        draftController = viewModel.draftController,
                                        sessions = chatSessions,
                                        decryptText = { viewModel.decrypt(it) },
                                        loadDisplayText = viewModel::loadDisplayText,
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
                                        coverage = alertCoverage,
                                        connectionStatus = connectionStatus,
                                        hostIp = "${nodeConfig.hostIp}:${nodeConfig.port}",
                                        onAcknowledgeAlert = { viewModel.acknowledgeAlert(it) },
                                        onApproveAction = { id, approved -> viewModel.submitApproval(id, approved) },
                                        onRunAnalysis = { analysisName -> viewModel.runPlaybook(analysisName) },
                                        onMarkAllRead = { viewModel.markAllNotificationsRead() }
                                    )

                                    ScreenTab.FILES -> LocalDraftForm(viewModel.draftController, "vault") { draft -> DocumentVaultScreen(
                                        documents = documents,
                                        loadDocument = viewModel.repository::openDocument,
                                        lastSavedCaptureId = lastSavedCaptureId,
                                        decryptText = { viewModel.decrypt(it) },
                                        documentSaved = documentSaved,
                                        onSaveDocument = { name, type, content, tags ->
                                            viewModel.saveDocument(name, type, content, tags)
                                        },
                                        onDeleteDocument = { viewModel.deleteDocument(it) },
                                        onSaveEvidence = viewModel::captureEvidence,
                                        onPickEvidence = { pickEvidence("vault", it) },
                                        onTakePhoto = { takePhoto("vault") },
                                        incomingEvidence = pendingEvidence?.takeIf { it.route == "vault" }?.attachment,
                                        onConsumeEvidence = viewModel::clearPendingEvidence,
                                        loadUnsentDraft = { draft.form }, draftState = draft,
                                        onDraftFieldsChange = draft::update,
                                        onSendDocToChat = { title, content ->
                                            viewModel.createNewChat("Doc: $title", "Analyze this report:\n\n$content")
                                        }
                                    ) }

                                    ScreenTab.SETTINGS -> NodeSettingsScreen(
                                        appearance = appearance,
                                        onAppearanceChange = { appearance = it; appearancePreferences.save(it) },
                                        currentConfig = nodeConfig,
                                        connectionStatus = connectionStatus,
                                        secureClinicalMode = secureClinicalMode,
                                        allowScreenshots = allowScreenshots,
                                        onAllowScreenshotsChange = ::updateAllowScreenshots,
                                        sessionMinutes = sessionMinutes, onRequestSessionMinutesChange = ::requestSessionChange,
                                        screenLockState = screenLockState, sessionPolicyError = sessionChangeError ?: sessionPolicyError,
                                        autoSaveDrafts = autoSaveDrafts, onAutoSaveDraftsChange = viewModel.draftController::setAutoSave,
                                        onSaveConfig = {
                                            val requestPermission = it.backgroundNotificationsEnabled && !nodeConfig.backgroundNotificationsEnabled
                                            viewModel.updateNodeConfig(it) { if (requestPermission) requestNotificationPermission() }
                                        },
                                        onToggleSecureClinicalMode = { viewModel.setSecureClinicalMode(it) },
                                        onTestConnection = { viewModel.triggerSync() },
                                        onLockVault = { lockProtectedWorkspace() },
                                        pairingState = pairingState, onPairLink = viewModel::beginPairing,
                                        onCancelPairing = viewModel::cancelPairing,
                                        onScanPairingQr = { scanningPairing = true }
                                    )

                                    ScreenTab.CAPTURE -> LocalDraftForm(viewModel.draftController, "capture") { draft -> FacilityCaptureScreen(
                                        snapshot = workflowState,
                                        lastSavedCaptureId = lastSavedCaptureId,
                                        busy = workflowBusy,
                                        error = vaultError,
                                        onCapture = { text, kind, location, owner, due, channel, evidence -> viewModel.createCapture(text, kind, location, owner, due, channel, evidence) },
                                        draftController = viewModel.draftController,
                                        onRefresh = viewModel::refreshWorkflows,
                                        onUpdateAction = viewModel::updateWorkflowAction,
                                        onSaveDraft = viewModel::saveWorkflowDraft,
                                        onAcknowledge = viewModel::acknowledgeFollowUp,
                                        onBack = { currentTab = ScreenTab.HOME },
                                        onPickEvidence = { pickEvidence("capture", it) },
                                        onTakePhoto = { takePhoto("capture") },
                                        incomingEvidence = pendingEvidence?.takeIf { it.route == "capture" }?.attachment,
                                        onConsumeEvidence = viewModel::clearPendingEvidence,
                                        loadUnsentDraft = { draft.form }, draftState = draft,
                                        onDraftFieldsChange = draft::update
                                    ) }
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}
