package com.example.data.repository

import com.example.data.crypto.EncryptionManager
import com.example.data.local.AppDatabase
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.BatteryOptimizationMode
import com.example.data.model.ChatAttachment
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.data.model.NodeStats
import com.example.data.model.SyncResult
import com.example.data.network.DesktopNodeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class PrivateAIRepository(
    private val database: AppDatabase,
    private val nodeService: DesktopNodeService = DesktopNodeService()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val chatDao = database.chatDao()
    private val documentDao = database.documentDao()
    private val taskDao = database.taskDao()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(System.currentTimeMillis() - 45_000)
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _nodeConfig = MutableStateFlow(DesktopNodeConfig())
    val nodeConfig: StateFlow<DesktopNodeConfig> = _nodeConfig.asStateFlow()

    private val _nodeStats = MutableStateFlow(NodeStats())
    val nodeStats: StateFlow<NodeStats> = _nodeStats.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Observable Room queries
    val chatSessions: Flow<List<EncryptedChatSession>> = chatDao.getAllSessions()
    val documents: Flow<List<EncryptedDocument>> = documentDao.getAllDocuments()
    val tasks: Flow<List<DesktopTask>> = taskDao.getAllTasks()
    val notifications: Flow<List<DesktopNotification>> = taskDao.getAllNotifications()

    init {
        scope.launch {
            seedInitialDataIfNeeded()
            startAdaptiveSyncLoop()
        }
    }

    fun getMessagesForSession(sessionId: String): Flow<List<EncryptedChatMessage>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    fun getSessionById(sessionId: String): Flow<EncryptedChatSession?> {
        return chatDao.getSessionById(sessionId)
    }

    fun getDocumentById(id: String): Flow<EncryptedDocument?> {
        return documentDao.getDocumentById(id)
    }

    fun updateConfig(newConfig: DesktopNodeConfig) {
        _nodeConfig.value = newConfig
        scope.launch {
            checkConnection()
        }
    }

    suspend fun checkConnection(): Boolean {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val isHealthy = nodeService.checkHealth(_nodeConfig.value)
        _connectionStatus.value = if (isHealthy) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
        if (isHealthy) {
            _nodeStats.value = nodeService.getNodeStats(_nodeConfig.value)
        }
        return isHealthy
    }

    fun toggleSimulatedDesktopNode(enabled: Boolean) {
        _nodeConfig.value = _nodeConfig.value.copy(simulatedNodeEnabled = enabled)
        scope.launch {
            checkConnection()
        }
    }

    suspend fun triggerSync(): SyncResult {
        if (_isSyncing.value) return SyncResult(success = true)
        _isSyncing.value = true
        _connectionStatus.value = ConnectionStatus.SYNCING

        return try {
            val config = _nodeConfig.value
            val isConnected = checkConnection()

            if (!isConnected) {
                _isSyncing.value = false
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return SyncResult(success = false, errorMessage = "Desktop node at ${config.hostIp}:${config.port} unreachable.")
            }

            // 1. Process any messages queued while mobile was offline
            val queuedMessages = chatDao.getQueuedOfflineMessages()
            var syncedCount = queuedMessages.size

            for (queued in queuedMessages) {
                val decryptedPrompt = EncryptionManager.decrypt(queued.encryptedContent)
                val assistantReply = nodeService.dispatchInferenceToDesktop(
                    sessionId = queued.sessionId,
                    userPrompt = decryptedPrompt,
                    config = config,
                    attachmentName = queued.attachmentName,
                    attachmentType = queued.attachmentType
                )
                chatDao.updateMessageStatus(queued.id, "SYNCED_DESKTOP")
                chatDao.insertMessage(assistantReply)
                syncedCount++
            }

            // 2. Pull updates from desktop
            val desktopSync = nodeService.pullDesktopSync(config)
            taskDao.insertNotifications(desktopSync.newNotifications)
            taskDao.insertTasks(desktopSync.updatedTasks)
            syncedCount += desktopSync.newNotifications.size + desktopSync.updatedTasks.size

            // 3. Update stats & timestamps
            _lastSyncTimestamp.value = System.currentTimeMillis()
            _nodeStats.value = nodeService.getNodeStats(config)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _isSyncing.value = false

            SyncResult(success = true, itemsSynced = syncedCount, timestamp = _lastSyncTimestamp.value)
        } catch (e: Exception) {
            _isSyncing.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            SyncResult(success = false, errorMessage = e.localizedMessage ?: "Sync error")
        }
    }

    suspend fun createNewSession(title: String, initialPrompt: String? = null): String {
        val sessionId = UUID.randomUUID().toString()
        val session = EncryptedChatSession(
            id = sessionId,
            title = title,
            createdAt = System.currentTimeMillis(),
            lastMessagePreviewEncrypted = if (initialPrompt != null) EncryptionManager.encrypt(initialPrompt) else "",
            lastMessageTimestamp = System.currentTimeMillis(),
            modelUsed = _nodeStats.value.loadedModel,
            isSynced = true
        )
        chatDao.insertSession(session)

        if (!initialPrompt.isNullOrBlank()) {
            sendMessage(sessionId, initialPrompt)
        }
        return sessionId
    }

    private var activeStreamJob: kotlinx.coroutines.Job? = null

    fun stopStreaming() {
        activeStreamJob?.cancel()
        activeStreamJob = null
    }

    suspend fun sendMessage(
        sessionId: String,
        prompt: String,
        attachment: ChatAttachment? = null
    ) {
        val userEncrypted = EncryptionManager.encrypt(prompt)
        val isDesktopConnected = _connectionStatus.value == ConnectionStatus.CONNECTED

        val encryptedAttachmentData = attachment?.let { EncryptionManager.encrypt(it.base64Data) }

        val userMessage = EncryptedChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sender = "user",
            encryptedContent = userEncrypted,
            timestamp = System.currentTimeMillis(),
            status = if (isDesktopConnected) "SYNCED_DESKTOP" else "QUEUED_OFFLINE",
            modelName = _nodeStats.value.loadedModel,
            attachmentName = attachment?.name,
            attachmentType = attachment?.type,
            attachmentDataEncrypted = encryptedAttachmentData,
            attachmentSize = attachment?.sizeBytes ?: 0L
        )
        chatDao.insertMessage(userMessage)

        // Update session preview
        val session = chatDao.getSessionById(sessionId).first()
        if (session != null) {
            val previewText = if (attachment != null) "[${attachment.name}] $prompt" else prompt
            chatDao.insertSession(
                session.copy(
                    lastMessagePreviewEncrypted = EncryptionManager.encrypt(previewText),
                    lastMessageTimestamp = System.currentTimeMillis()
                )
            )
        }

        if (isDesktopConnected) {
            activeStreamJob?.cancel()
            activeStreamJob = scope.launch {
                try {
                    val replyId = UUID.randomUUID().toString()
                    val modelUsed = if (attachment?.type == "IMAGE") {
                        "Llama-3.2-Vision (Workstation Local)"
                    } else {
                        "Llama-3.3-70B-Instruct (Desktop Node)"
                    }

                    val initialPlaceholder = EncryptedChatMessage(
                        id = replyId,
                        sessionId = sessionId,
                        sender = "assistant",
                        encryptedContent = EncryptionManager.encrypt("Thinking on workstation GPU..."),
                        timestamp = System.currentTimeMillis(),
                        status = "STREAMING",
                        promptTokens = prompt.length / 4,
                        completionTokens = 0,
                        modelName = modelUsed
                    )
                    chatDao.insertMessage(initialPlaceholder)

                    var lastEmittedText = ""
                    nodeService.streamInferenceFromDesktop(
                        prompt = prompt,
                        attachmentName = attachment?.name,
                        attachmentType = attachment?.type
                    ).collect { partialText ->
                        lastEmittedText = partialText
                        val partialEncrypted = EncryptionManager.encrypt(partialText)
                        chatDao.insertMessage(
                            initialPlaceholder.copy(
                                encryptedContent = partialEncrypted,
                                completionTokens = partialText.length / 4,
                                status = "STREAMING"
                            )
                        )
                    }

                    val finalEncrypted = EncryptionManager.encrypt(lastEmittedText)
                    chatDao.insertMessage(
                        initialPlaceholder.copy(
                            encryptedContent = finalEncrypted,
                            completionTokens = lastEmittedText.length / 4,
                            status = "SYNCED_DESKTOP"
                        )
                    )
                    _lastSyncTimestamp.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        // Stream stopped by user
                    } else {
                        chatDao.updateMessageStatus(userMessage.id, "QUEUED_OFFLINE")
                    }
                } finally {
                    activeStreamJob = null
                }
            }
        }
    }

    suspend fun saveDocument(
        fileName: String,
        fileType: String,
        plainContent: String,
        tags: String = "Private, E2EE"
    ) {
        val encrypted = EncryptionManager.encrypt(plainContent)
        val hash = EncryptionManager.computeSha256(plainContent)
        val doc = EncryptedDocument(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            fileType = fileType,
            encryptedContent = encrypted,
            checksumSha256 = hash,
            fileSizeBytes = plainContent.toByteArray(Charsets.UTF_8).size.toLong(),
            updatedAt = System.currentTimeMillis(),
            syncedWithDesktop = true,
            tags = tags
        )
        documentDao.insertDocument(doc)
    }

    suspend fun deleteDocument(id: String) {
        documentDao.deleteDocument(id)
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSession(sessionId)
    }

    suspend fun markNotificationRead(id: String) {
        taskDao.markNotificationRead(id)
    }

    suspend fun markAllNotificationsRead() {
        taskDao.markAllNotificationsRead()
    }

    suspend fun acknowledgeAlert(id: String) {
        taskDao.acknowledgeNotification(id)
    }

    private val _secureClinicalMode = MutableStateFlow(false)
    val secureClinicalMode: StateFlow<Boolean> = _secureClinicalMode.asStateFlow()

    private val _modelProcessingMode = MutableStateFlow("Local") // "Local", "Smart Hybrid", "Cloud", "Local Only"
    val modelProcessingMode: StateFlow<String> = _modelProcessingMode.asStateFlow()

    fun setSecureClinicalMode(enabled: Boolean) {
        _secureClinicalMode.value = enabled
    }

    fun setModelProcessingMode(mode: String) {
        _modelProcessingMode.value = mode
    }

    suspend fun startAgent(name: String) {
        val task = nodeService.startAgent(name, _nodeConfig.value)
        taskDao.insertTask(task)
    }

    suspend fun runPlaybook(name: String) {
        val task = nodeService.runPlaybook(name, _nodeConfig.value)
        taskDao.insertTask(task)
    }

    suspend fun cancelTask(taskId: String) {
        nodeService.cancelTask(taskId, _nodeConfig.value)
        taskDao.updateTaskStatus(taskId, "FAILED")
    }

    suspend fun retryTask(taskId: String) {
        nodeService.retryTask(taskId, _nodeConfig.value)
        taskDao.updateTaskStatus(taskId, "RUNNING")
    }

    suspend fun submitApproval(taskId: String, approved: Boolean) {
        nodeService.submitApproval(taskId, approved, _nodeConfig.value)
        taskDao.updateTaskStatus(taskId, if (approved) "RUNNING" else "REJECTED")
    }

    private fun startAdaptiveSyncLoop() {
        scope.launch {
            while (true) {
                val mode = _nodeConfig.value.batteryMode
                val delayTimeMillis = when (mode) {
                    BatteryOptimizationMode.REAL_TIME -> 30_000L // 30 sec when active
                    BatteryOptimizationMode.SMART_ECO -> 5 * 60_000L // 5 min interval
                    BatteryOptimizationMode.MANUAL_ONLY -> Long.MAX_VALUE // sleep until triggered
                }

                if (delayTimeMillis < Long.MAX_VALUE) {
                    delay(delayTimeMillis)
                    if (_nodeConfig.value.batteryMode != BatteryOptimizationMode.MANUAL_ONLY) {
                        triggerSync()
                    }
                } else {
                    delay(60_000L)
                }
            }
        }
    }

    private suspend fun seedInitialDataIfNeeded() {
        val existingSessions = chatDao.getAllSessions().first()
        if (existingSessions.isNotEmpty()) return

        // Seed rich initial offline private AI sessions
        val session1Id = "session-sec-1"
        val s1Prompt = "Explain our zero-trust local network encryption protocol for the desktop node"
        val s1Answer = "### Private-AI Zero-Trust LAN Architecture\n\n" +
                "1. **No External Cloud Dependency**: All inference runs directly on your local workstation.\n" +
                "2. **AES-256-GCM Encryption**: Payloads transferred over the local network are encrypted before socket write.\n" +
                "3. **Zero Subscription Required**: Runs 100% peer-to-peer on LAN or direct Wi-Fi.\n" +
                "4. **Mobilesnappy Offload**: Complex tensor calculations occur on the workstation GPU while your Android device only renders the encrypted stream."

        val session1 = EncryptedChatSession(
            id = session1Id,
            title = "Zero-Trust LAN Protocol & E2EE",
            createdAt = System.currentTimeMillis() - 7200_000,
            lastMessagePreviewEncrypted = EncryptionManager.encrypt(s1Prompt),
            lastMessageTimestamp = System.currentTimeMillis() - 7100_000,
            modelUsed = "Llama-3.3-70B-Instruct-Q4",
            isSynced = true
        )

        val session2Id = "session-sec-2"
        val s2Prompt = "Review confidential Q3 financial model & cashflow forecast"
        val s2Answer = "Financial review completed on local GPU:\n- Burn rate decreased 18%\n- Runway estimated at 26 months\n- Integrity check: Document SHA-256 verified against desktop ledger."

        val session2 = EncryptedChatSession(
            id = session2Id,
            title = "Confidential Financial Analysis",
            createdAt = System.currentTimeMillis() - 14400_000,
            lastMessagePreviewEncrypted = EncryptionManager.encrypt(s2Prompt),
            lastMessageTimestamp = System.currentTimeMillis() - 14200_000,
            modelUsed = "Llama-3.3-70B-Instruct-Q4",
            isSynced = true
        )

        chatDao.insertSessions(listOf(session1, session2))

        val messages = listOf(
            EncryptedChatMessage(
                id = "msg-1",
                sessionId = session1Id,
                sender = "user",
                encryptedContent = EncryptionManager.encrypt(s1Prompt),
                timestamp = System.currentTimeMillis() - 7200_000,
                status = "SYNCED_DESKTOP"
            ),
            EncryptedChatMessage(
                id = "msg-2",
                sessionId = session1Id,
                sender = "assistant",
                encryptedContent = EncryptionManager.encrypt(s1Answer),
                timestamp = System.currentTimeMillis() - 7100_000,
                status = "SYNCED_DESKTOP",
                promptTokens = 48,
                completionTokens = 126
            ),
            EncryptedChatMessage(
                id = "msg-3",
                sessionId = session2Id,
                sender = "user",
                encryptedContent = EncryptionManager.encrypt(s2Prompt),
                timestamp = System.currentTimeMillis() - 14400_000,
                status = "SYNCED_DESKTOP"
            ),
            EncryptedChatMessage(
                id = "msg-4",
                sessionId = session2Id,
                sender = "assistant",
                encryptedContent = EncryptionManager.encrypt(s2Answer),
                timestamp = System.currentTimeMillis() - 14200_000,
                status = "SYNCED_DESKTOP",
                promptTokens = 35,
                completionTokens = 84
            )
        )
        chatDao.insertMessages(messages)

        // Seed Encrypted Shared Documents
        val doc1Content = "# Private AI Security Architecture & Specs\n\n" +
                "## 1. Local Network Handshake\n" +
                "- Key Exchange: Pre-shared Ed25519 pairing token\n" +
                "- Symmetric Cipher: AES-256-GCM with unique 96-bit nonce per transmission\n\n" +
                "## 2. Hardware Allocation\n" +
                "- Host: NVIDIA RTX 4090 (24GB VRAM)\n" +
                "- Mobile Companion: Zero model weights loaded into mobile memory\n\n" +
                "## 3. Battery Impact\n" +
                "- Deep sleep during idle states\n" +
                "- Background sync throttled to smart unmetered Wi-Fi intervals\n"

        val doc2Content = "{\"project\":\"Private-AI-OG\",\"status\":\"production_ready\",\"encryption\":\"AES-256-GCM\",\"biometrics_required\":true,\"nodes\":[{\"name\":\"Desktop-Host-01\",\"ip\":\"192.168.1.155\"}]}"

        val doc1 = EncryptedDocument(
            id = "doc-1",
            fileName = "Private_AI_Architecture_Spec.md",
            fileType = "MARKDOWN",
            encryptedContent = EncryptionManager.encrypt(doc1Content),
            checksumSha256 = EncryptionManager.computeSha256(doc1Content),
            fileSizeBytes = doc1Content.toByteArray().size.toLong(),
            updatedAt = System.currentTimeMillis() - 3600_000,
            syncedWithDesktop = true,
            tags = "Confidential, Architecture, E2EE"
        )

        val doc2 = EncryptedDocument(
            id = "doc-2",
            fileName = "Node_Pairing_Manifest.json",
            fileType = "JSON",
            encryptedContent = EncryptionManager.encrypt(doc2Content),
            checksumSha256 = EncryptionManager.computeSha256(doc2Content),
            fileSizeBytes = doc2Content.toByteArray().size.toLong(),
            updatedAt = System.currentTimeMillis() - 7200_000,
            syncedWithDesktop = true,
            tags = "Config, Keys, Network"
        )

        documentDao.insertDocuments(listOf(doc1, doc2))

        // Seed Desktop Tasks
        val task1 = DesktopTask(
            id = "task-seed-1",
            title = "Vector DB Embedding Sync (Llama-Index)",
            status = "RUNNING",
            progress = 0.72f,
            modelName = "bge-large-en-v1.5",
            startedAt = System.currentTimeMillis() - 1200_000
        )
        val task2 = DesktopTask(
            id = "task-seed-2",
            title = "Batch Document OCR & Entity Extraction",
            status = "COMPLETED",
            progress = 1.0f,
            modelName = "Llama-3.3-70B-Instruct",
            startedAt = System.currentTimeMillis() - 5400_000,
            completedAt = System.currentTimeMillis() - 3600_000,
            resultSummary = "128 pages digitized and ingested into encrypted knowledge store."
        )
        taskDao.insertTasks(listOf(task1, task2))

        // Seed Notifications
        val notif1 = DesktopNotification(
            id = "notif-1",
            title = "Critical Clinical Finding",
            message = "Elevated fall risk & psychotropic titration detected for resident in Unit 3 requiring review.",
            timestamp = System.currentTimeMillis() - 15 * 60_000,
            isRead = false,
            isAcknowledged = false,
            severity = "CRITICAL",
            category = "CLINICAL",
            requiresApproval = false,
            assignedTo = "Director of Nursing",
            relatedAction = "Review Medication Orders"
        )
        val notif2 = DesktopNotification(
            id = "notif-2",
            title = "Agent Requires Approval",
            message = "Staffing Analysis Agent proposes automated shift-fill broadcast to float pool.",
            timestamp = System.currentTimeMillis() - 42 * 60_000,
            isRead = false,
            isAcknowledged = false,
            severity = "HIGH",
            category = "AGENT_APPROVAL",
            requiresApproval = true,
            assignedTo = "Administrator",
            relatedAction = "Approve Shift Broadcast"
        )
        taskDao.insertNotifications(listOf(notif1, notif2))
    }
}
