package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.*
import com.example.data.network.GatewayException
import com.example.data.network.DesktopNodeService
import com.example.data.network.GatewayAuthorizationEpoch
import com.example.data.network.PairingConfigParser
import com.example.data.notifications.FollowUpNotifications
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.example.ui.pairing.PairingUiState

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val biometricAuthManager = BiometricAuthManager(application)
    val repository = run {
        EncryptionManager.initialize(application)
        lateinit var configured: PrivateAIRepository
        val transport = DesktopNodeService { config ->
            if (!EncryptionManager.hasCurrentAccess()) null else configured.authorizationGeneration(config)?.let {
                GatewayAuthorizationEpoch(EncryptionManager.accessGeneration, it)
            }
        }
        configured = PrivateAIRepository(AppDatabase.getInstance(application), nodeService = transport, configStore = SecureConfigStore(application),
            demoDatabase = AppDatabase.getDemoInstance(application))
        configured
    }
    val connectionStatus get() = repository.connectionStatus
    val lastConnectionCheck get() = repository.lastConnectionCheck
    val lastSyncTimestamp get() = repository.lastSyncTimestamp
    val nodeConfig get() = repository.nodeConfig
    val nodeStats get() = repository.nodeStats
    val isSyncing get() = repository.isSyncing
    val isUnlocked get() = biometricAuthManager.isUnlocked
    val vaultReady get() = repository.vaultReady
    val lastError get() = repository.lastError
    val workflowState get() = repository.workflowState
    private val _workflowBusy = MutableStateFlow(false)
    val workflowBusy = _workflowBusy.asStateFlow()
    private val _pendingEvidence = MutableStateFlow<PendingEvidence?>(null)
    val pendingEvidence = _pendingEvidence.asStateFlow()
    private val _lastSavedCaptureId = MutableStateFlow<String?>(null)
    val lastSavedCaptureId = _lastSavedCaptureId.asStateFlow()
    private val protectedDrafts = mutableMapOf<String, String>()
    var stopMeetingRecording: (() -> Unit)? = null
    var stopPairingScanner: (() -> Unit)? = null
    private var pairingJob: Job? = null
    private var pairingGeneration = 0L
    private val _pairingState = MutableStateFlow(PairingUiState())
    val pairingState = _pairingState.asStateFlow()
    private var activeOperations = 0
    val chatSessions = repository.chatSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documents = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tasks = repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notifications = repository.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId = _activeSessionId.asStateFlow()
    val activeSessionMessages = _activeSessionId.flatMapLatest { id -> id?.let(repository::getMessagesForSession) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeSession = _activeSessionId.flatMapLatest { id -> id?.let(repository::getSessionById) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _selectedDocument = MutableStateFlow<EncryptedDocument?>(null)
    val selectedDocument = _selectedDocument.asStateFlow()
    private val _userFeedback = MutableStateFlow<String?>(null)
    val userFeedback = _userFeedback.asStateFlow()
    val secureClinicalMode get() = repository.secureClinicalMode
    val modelProcessingMode get() = repository.modelProcessingMode
    private val _isStreamingResponse = MutableStateFlow(false)
    val isStreamingResponse = _isStreamingResponse.asStateFlow()

    init {
        viewModelScope.launch {
            biometricAuthManager.isUnlocked.collectLatest { unlocked ->
                if (unlocked) {
                    val opening = repository.onUnlocked()
                    try { opening.join(); if (vaultReady.value) configureNotifications(nodeConfig.value) }
                    finally { if (!isUnlocked.value) opening.cancel() }
                } else {
                    stopPairingScanner?.invoke(); cancelPairing()
                    stopMeetingRecording?.invoke(); repository.onLocked(); _pendingEvidence.value = null; _lastSavedCaptureId.value = null; _selectedDocument.value = null
                }
            }
        }
    }

    fun acceptPendingEvidence(route: String, attachment: ChatAttachment) {
        check(vaultReady.value)
        _pendingEvidence.value = PendingEvidence(route, attachment)
    }
    fun clearPendingEvidence() { _pendingEvidence.value = null }
    private fun draftKey(route: String) = PairingConfigParser.destinationIdentity(nodeConfig.value) + ":" + route
    fun saveUnsentDraft(route: String, text: String) {
        if (vaultReady.value) try { protectedDrafts[draftKey(route)] = EncryptionManager.encrypt(text) }
        catch (_: VaultUnavailableException) { biometricAuthManager.lock() }
    }
    fun restoreUnsentDraft(route: String): String = try {
        if (!vaultReady.value) "" else protectedDrafts[draftKey(route)]?.let(EncryptionManager::decrypt).orEmpty()
    } catch (_: Exception) { "" }

    private fun operation(message: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            activeOperations++
            _workflowBusy.value = true
            try { action(); if (message != null) _userFeedback.value = message }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _userFeedback.value = if (e is GatewayException) e.message else "This request could not be completed. Check the protected workspace and pairing details."
            } finally { activeOperations--; _workflowBusy.value = activeOperations > 0 }
        }
    }
    fun clearFeedback() { _userFeedback.value = null }
    fun selectSession(id: String?) { _activeSessionId.value = id }
    fun selectDocument(document: EncryptedDocument?) { _selectedDocument.value = document }
    fun triggerSync() = operation {
        val result = repository.triggerSync()
        _userFeedback.value = if (result.success) "Desktop check completed. ${result.itemsSynced} records updated." else result.errorMessage ?: "Desktop check failed."
    }
    fun sendMessage(prompt: String, attachment: ChatAttachment? = null) {
        val id = _activeSessionId.value ?: return
        if (prompt.isBlank() && attachment == null) return
        operation {
            _isStreamingResponse.value = true
            try {
                repository.sendMessage(id, prompt, attachment)
                _userFeedback.value = if (attachment != null) "Evidence note saved or queued for review. Image contents were not interpreted."
                    else if (connectionStatus.value == ConnectionStatus.DISCONNECTED) "Saved on this phone; pending desktop delivery." else null
            } finally { _isStreamingResponse.value = false }
        }
    }
    fun decrypt(content: String): String = try { if (vaultReady.value) EncryptionManager.decrypt(content) else "" }
        catch (_: VaultUnavailableException) { biometricAuthManager.lock(); "" }
    fun decryptAttachmentBitmap(content: String): Bitmap? = if (vaultReady.value) AttachmentUtils.base64ToBitmap(decrypt(content)) else null
    fun createNewChat(title: String, initialPrompt: String? = null) = operation { _activeSessionId.value = repository.createNewSession(title, initialPrompt) }
    fun deleteSession(id: String) = operation("Conversation removed from this phone's cache. Desktop history is retained.") {
        if (_activeSessionId.value == id) _activeSessionId.value = null
        repository.deleteSession(id)
    }
    fun saveDocument(name: String, type: String, content: String, tags: String) = operation("Note protected and saved on this phone. It has not been uploaded.") {
        repository.saveDocument(name, type, content, tags)
    }
    fun deleteDocument(id: String) = operation {
        if (_selectedDocument.value?.id == id) _selectedDocument.value = null
        repository.deleteDocument(id)
    }
    fun updateNodeConfig(config: DesktopNodeConfig) = operation("Pairing settings saved. Check the connection to verify desktop access.") {
        if (config.simulatedNodeEnabled != nodeConfig.value.simulatedNodeEnabled) { _activeSessionId.value = null; _selectedDocument.value = null }
        repository.updateConfig(config)
        configureNotifications(config)
    }

    fun beginPairing(link: String) {
        cancelPairing()
        if (!vaultReady.value || !EncryptionManager.hasCurrentAccess()) {
            _pairingState.value = PairingUiState(error = "Unlock the protected phone workspace before pairing.")
            return
        }
        val attempt = pairingGeneration
        val authorization = EncryptionManager.accessGeneration
        _pairingState.value = PairingUiState(connecting = true)
        pairingJob = viewModelScope.launch {
            try {
                val completion = repository.pairSecure(link)
                if (attempt == pairingGeneration && EncryptionManager.hasCurrentAccess() &&
                    authorization == EncryptionManager.accessGeneration) {
                    _activeSessionId.value = null; _selectedDocument.value = null
                    configureNotifications(nodeConfig.value)
                    _pairingState.value = PairingUiState(completion = completion)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == pairingGeneration) _pairingState.value = PairingUiState(error =
                    if (e is GatewayException) e.message else "Pairing could not be verified. Show a fresh QR or secure link on the desktop, check the private network and try again.")
            } finally {
                if (attempt == pairingGeneration) {
                    pairingJob = null
                    if (_pairingState.value.connecting) _pairingState.value = PairingUiState()
                }
            }
        }
    }

    fun cancelPairing() {
        pairingGeneration++
        pairingJob?.cancel(); pairingJob = null
        repository.cancelPairing()
        _pairingState.value = PairingUiState()
    }
    fun toggleSimulatedDesktop(enabled: Boolean) = operation("Workspace changed. Demo uses a separate database and never contacts the desktop.") {
        _activeSessionId.value = null; _selectedDocument.value = null; repository.toggleSimulatedDesktopNode(enabled)
        configureNotifications(repository.nodeConfig.value)
    }
    fun setSecureClinicalMode(enabled: Boolean) = operation("Phone chat privacy preference updated. Desktop processing policy remains controlled on the PC.") {
        repository.setSecureClinicalMode(enabled).join()
    }
    fun setModelProcessingMode(mode: String) = operation { repository.setModelProcessingMode(mode) }
    fun startAgent(name: String) = operation("Demo task created. No workstation execution occurred.") { repository.startAgent(name) }
    fun runPlaybook(name: String) = operation("Demo playbook created. No workstation execution occurred.") { repository.runPlaybook(name) }
    fun cancelTask(id: String) = operation { repository.cancelTask(id) }
    fun retryTask(id: String) = operation { repository.retryTask(id) }
    fun submitApproval(id: String, approved: Boolean) = operation { repository.submitApproval(id, approved) }
    fun acknowledgeAlert(id: String) = operation("Marked read on this phone. This does not close a clinical alert.") { repository.acknowledgeAlert(id) }
    fun markNotificationRead(id: String) = operation { repository.markNotificationRead(id) }
    fun markAllNotificationsRead() = operation { repository.markAllNotificationsRead() }
    fun stopStreaming() { repository.stopStreaming(); _isStreamingResponse.value = false; _userFeedback.value = "Request stopped. Review pending delivery before submitting another request." }
    fun unlockWithPin(pin: String) = biometricAuthManager.unlockWithPin(pin)
    fun lockVault() { stopPairingScanner?.invoke(); cancelPairing(); stopMeetingRecording?.invoke(); repository.onLocked(); biometricAuthManager.lock(); _activeSessionId.value = null; _selectedDocument.value = null }
    fun refreshWorkflows() = triggerSync()
    fun createCapture(text: String, kind: String, location: String, owner: String, dueAt: Long?, channel: String?, attachment: ChatAttachment?) =
        operation("Note saved or queued. Review the desktop's proposed actions; no email or WhatsApp draft has been sent.") {
            _lastSavedCaptureId.value = repository.createCapture(text, kind, location, owner, dueAt, channel, attachment)
        }
    fun captureEvidence(attachment: ChatAttachment) = createCapture("Evidence supplied for human review.", "note", "", "", null, null, attachment)
    fun updateWorkflowAction(item: WorkflowAction, owner: String, dueAt: Long?, note: String, complete: Boolean) =
        operation("Action updated on the desktop.") { repository.updateAction(item, owner, dueAt, note, complete) }
    fun saveWorkflowDraft(item: WorkflowDraft, recipient: String, body: String, subject: String) =
        operation("Internal draft saved for review. No message was sent.") { repository.saveDraft(item, recipient, body, subject) }
    fun acknowledgeFollowUp(id: String) = operation("Follow-up notification acknowledged. The action itself remains open.") { repository.acknowledgeFollowUp(id) }
    private fun configureNotifications(config: DesktopNodeConfig) {
        val enabled = config.backgroundNotificationsEnabled && !config.simulatedNodeEnabled && config.notificationKey.isNotBlank()
        RestrictedNotificationStore(getApplication()).save(config, enabled)
        FollowUpNotifications.configure(getApplication(), enabled)
    }
    override fun onCleared() { repository.close(); super.onCleared() }
}
