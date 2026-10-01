package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import com.example.data.crypto.AttachmentUtils
import com.example.data.crypto.BiometricAuthManager
import com.example.data.crypto.EncryptionManager
import com.example.data.local.AppDatabase
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.ChatAttachment
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.data.model.NodeStats
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    val repository = PrivateAIRepository(database)
    val biometricAuthManager = BiometricAuthManager(application)

    val connectionStatus: StateFlow<ConnectionStatus> = repository.connectionStatus
    val lastSyncTimestamp: StateFlow<Long> = repository.lastSyncTimestamp
    val nodeConfig: StateFlow<DesktopNodeConfig> = repository.nodeConfig
    val nodeStats: StateFlow<NodeStats> = repository.nodeStats
    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val isUnlocked: StateFlow<Boolean> = biometricAuthManager.isUnlocked

    val chatSessions: StateFlow<List<EncryptedChatSession>> = repository.chatSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<EncryptedDocument>> = repository.documents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tasks: StateFlow<List<DesktopTask>> = repository.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<DesktopNotification>> = repository.notifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    val activeSessionMessages: StateFlow<List<EncryptedChatMessage>> = _activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessagesForSession(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSession: StateFlow<EncryptedChatSession?> = _activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getSessionById(sessionId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedDocument = MutableStateFlow<EncryptedDocument?>(null)
    val selectedDocument: StateFlow<EncryptedDocument?> = _selectedDocument.asStateFlow()

    private val _userFeedback = MutableStateFlow<String?>(null)
    val userFeedback: StateFlow<String?> = _userFeedback.asStateFlow()

    fun clearFeedback() {
        _userFeedback.value = null
    }

    fun selectSession(sessionId: String?) {
        _activeSessionId.value = sessionId
    }

    fun selectDocument(document: EncryptedDocument?) {
        _selectedDocument.value = document
    }

    fun triggerSync() {
        viewModelScope.launch {
            val result = repository.triggerSync()
            if (result.success) {
                _userFeedback.value = "Synced ${result.itemsSynced} items with desktop node."
            } else {
                _userFeedback.value = result.errorMessage ?: "Sync failed"
            }
        }
    }

    fun sendMessage(prompt: String, attachment: ChatAttachment? = null) {
        val sessionId = _activeSessionId.value ?: return
        if (prompt.isBlank() && attachment == null) return
        viewModelScope.launch {
            repository.sendMessage(sessionId, prompt, attachment)
        }
    }

    fun decryptAttachmentBitmap(encryptedBase64: String): Bitmap? {
        val decryptedBase64 = EncryptionManager.decrypt(encryptedBase64)
        return AttachmentUtils.base64ToBitmap(decryptedBase64)
    }

    fun createNewChat(title: String, initialPrompt: String? = null) {
        viewModelScope.launch {
            val newId = repository.createNewSession(title, initialPrompt)
            _activeSessionId.value = newId
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            if (_activeSessionId.value == sessionId) {
                _activeSessionId.value = null
            }
            repository.deleteSession(sessionId)
        }
    }

    fun saveDocument(fileName: String, fileType: String, content: String, tags: String) {
        viewModelScope.launch {
            repository.saveDocument(fileName, fileType, content, tags)
            _userFeedback.value = "Document '$fileName' encrypted & stored in vault."
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            if (_selectedDocument.value?.id == id) {
                _selectedDocument.value = null
            }
            repository.deleteDocument(id)
        }
    }

    fun decrypt(encryptedContent: String): String {
        return EncryptionManager.decrypt(encryptedContent)
    }

    fun updateNodeConfig(config: DesktopNodeConfig) {
        repository.updateConfig(config)
        _userFeedback.value = "Desktop node connection updated."
    }

    fun toggleSimulatedDesktop(enabled: Boolean) {
        repository.toggleSimulatedDesktopNode(enabled)
    }

    val secureClinicalMode: StateFlow<Boolean> = repository.secureClinicalMode
    val modelProcessingMode: StateFlow<String> = repository.modelProcessingMode

    private val _isStreamingResponse = MutableStateFlow(false)
    val isStreamingResponse: StateFlow<Boolean> = _isStreamingResponse.asStateFlow()

    fun setSecureClinicalMode(enabled: Boolean) {
        repository.setSecureClinicalMode(enabled)
        _userFeedback.value = if (enabled) "Secure Clinical Mode activated (Screenshots blocked, PHI hidden)" else "Standard Clinical Mode"
    }

    fun setModelProcessingMode(mode: String) {
        repository.setModelProcessingMode(mode)
        _userFeedback.value = "AI Routing set to: $mode"
    }

    fun startAgent(name: String) {
        viewModelScope.launch {
            repository.startAgent(name)
            _userFeedback.value = "Agent '$name' dispatched to desktop runtime."
        }
    }

    fun runPlaybook(name: String) {
        viewModelScope.launch {
            repository.runPlaybook(name)
            _userFeedback.value = "Playbook '$name' started on workstation."
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            repository.cancelTask(taskId)
            _userFeedback.value = "Task cancelled on desktop node."
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            repository.retryTask(taskId)
            _userFeedback.value = "Task re-queued for execution."
        }
    }

    fun submitApproval(taskId: String, approved: Boolean) {
        viewModelScope.launch {
            repository.submitApproval(taskId, approved)
            _userFeedback.value = if (approved) "Action approved. Agent continuing execution." else "Action rejected."
        }
    }

    fun acknowledgeAlert(alertId: String) {
        viewModelScope.launch {
            repository.acknowledgeAlert(alertId)
            _userFeedback.value = "Alert acknowledged."
        }
    }

    fun stopStreaming() {
        repository.stopStreaming()
        _isStreamingResponse.value = false
        _userFeedback.value = "Response stream stopped."
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            repository.markNotificationRead(id)
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            repository.markAllNotificationsRead()
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val success = biometricAuthManager.unlockWithPin(pin)
        if (success) {
            _userFeedback.value = "Vault unlocked successfully."
        }
        return success
    }

    fun lockVault() {
        biometricAuthManager.lock()
        _userFeedback.value = "Vault locked."
    }
}
