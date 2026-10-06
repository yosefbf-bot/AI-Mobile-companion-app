package com.example.data.repository

import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.SecureConfigStore
import com.example.data.crypto.VaultCipher
import com.example.data.crypto.VaultRecords
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import com.example.data.model.*
import com.example.data.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateAIRepository(
    private val database: AppDatabase,
    private val nodeService: DesktopNodeService = DesktopNodeService(),
    private val configStore: SecureConfigStore? = null,
    private val demoDatabase: AppDatabase? = null,
    private val cipher: VaultCipher = EncryptionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selectedDatabase = MutableStateFlow(database)
    private val chatDao get() = selectedDatabase.value.chatDao()
    private val documentDao get() = selectedDatabase.value.documentDao()
    private val taskDao get() = selectedDatabase.value.taskDao()
    private val workflows = SharedWorkflowService(nodeService)
    private val delivery = Mutex()
    private val sync = Mutex()
    private var opening: Job? = null
    @Volatile private var workspaceGeneration = 0L
    private var polling: Job? = null
    private val activeRequests = java.util.concurrent.ConcurrentHashMap<Job, java.util.concurrent.atomic.AtomicInteger>()
    private val _vaultReady = MutableStateFlow(false)
    val vaultReady = _vaultReady.asStateFlow()
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()
    private val _lastSyncTimestamp = MutableStateFlow(0L)
    val lastSyncTimestamp = _lastSyncTimestamp.asStateFlow()
    private val _nodeConfig = MutableStateFlow(DesktopNodeConfig())
    val nodeConfig = _nodeConfig.asStateFlow()
    private val _nodeStats = MutableStateFlow(NodeStats())
    val nodeStats = _nodeStats.asStateFlow()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    private val _workflowState = MutableStateFlow(WorkflowSnapshot())
    val workflowState = _workflowState.asStateFlow()
    private val _secureClinicalMode = MutableStateFlow(true)
    val secureClinicalMode = _secureClinicalMode.asStateFlow()
    private val _modelProcessingMode = MutableStateFlow("Desktop settings")
    val modelProcessingMode = _modelProcessingMode.asStateFlow()

    val chatSessions = selectedDatabase.flatMapLatest { it.chatDao().getAllSessions() }
        .combine(vaultReady) { rows, ready -> if (ready) rows.map { VaultRecords.decode(it, cipher) } else emptyList() }
    val documents = selectedDatabase.flatMapLatest { it.documentDao().getAllDocuments() }
        .combine(vaultReady) { rows, ready -> if (ready) rows.map { VaultRecords.decode(it, cipher) } else emptyList() }
    val tasks = selectedDatabase.flatMapLatest { it.taskDao().getAllTasks() }
        .combine(vaultReady) { rows, ready -> if (ready) rows.map { VaultRecords.decode(it, cipher) } else emptyList() }
    val notifications = selectedDatabase.flatMapLatest { it.taskDao().getAllNotifications() }
        .combine(vaultReady) { rows, ready -> if (ready) rows.map { VaultRecords.decode(it, cipher) } else emptyList() }

    fun onUnlocked(): Job {
        val generation = ++workspaceGeneration
        opening?.cancel()
        return scope.launch {
        _vaultReady.value = false
        try {
            database.migrateLegacyContent(cipher)
            _nodeConfig.value = configStore?.load() ?: _nodeConfig.value
            selectWorkspace()
            selectedDatabase.value.migrateLegacyContent(cipher)
            ensureActive()
            check(generation == workspaceGeneration)
            _vaultReady.value = true
            if (_nodeConfig.value.simulatedNodeEnabled) seedDemo()
            _lastError.value = null
            polling?.cancel()
            polling = scope.launch {
                while (isActive && _vaultReady.value) {
                    delay(if (_nodeConfig.value.batteryMode == BatteryOptimizationMode.REAL_TIME) 30_000 else 300_000)
                    if (_nodeConfig.value.batteryMode != BatteryOptimizationMode.MANUAL_ONLY) coroutineScope { launch { triggerSync() }.join() }
                }
            }
            if (_nodeConfig.value.isPaired || _nodeConfig.value.simulatedNodeEnabled) checkConnection()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (generation == workspaceGeneration) _vaultReady.value = false
            _lastError.value = "The protected workspace could not be opened. Unlock again or use the recovery instructions; no data was erased."
        }
        }.also { opening = it }
    }

    fun onLocked() {
        _vaultReady.value = false
        ++workspaceGeneration
        opening?.cancel(); polling?.cancel(); activeRequests.keys.forEach { it.cancel() }; nodeService.cancelAll()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _nodeStats.value = NodeStats()
        _workflowState.value = WorkflowSnapshot()
        _lastSyncTimestamp.value = 0
        _isSyncing.value = false
    }

    private fun requireReady() { check(_vaultReady.value) { "Unlock the protected phone workspace first." } }
    private suspend fun <T> withTrackedRequest(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeRequests.compute(job) { _, count -> (count ?: java.util.concurrent.atomic.AtomicInteger()).apply { incrementAndGet() } }
        try { currentCoroutineContext().ensureActive(); return block() }
        finally { if (job != null) activeRequests.computeIfPresent(job) { _, count -> if (count.decrementAndGet() == 0) null else count } }
    }
    fun authorizationGeneration(config: DesktopNodeConfig): Long? =
        if (_vaultReady.value && config == _nodeConfig.value) workspaceGeneration else null
    private fun requireCurrent(config: DesktopNodeConfig, generation: Long) {
        requireReady()
        check(generation == workspaceGeneration && config == _nodeConfig.value) { "The protected workspace changed while this request was running." }
    }
    private fun selectWorkspace() {
        selectedDatabase.value = if (_nodeConfig.value.simulatedNodeEnabled)
            demoDatabase ?: throw IllegalStateException("An isolated demo workspace is not available.") else database
    }

    fun getMessagesForSession(sessionId: String) = selectedDatabase.flatMapLatest { it.chatDao().getMessagesForSession(sessionId) }
        .combine(vaultReady) { rows, ready -> if (ready) rows.map { VaultRecords.decode(it, cipher) } else emptyList() }
    fun getSessionById(sessionId: String) = selectedDatabase.flatMapLatest { it.chatDao().getSessionById(sessionId) }
        .combine(vaultReady) { row, ready -> if (ready && row != null) VaultRecords.decode(row, cipher) else null }
    fun getDocumentById(id: String) = selectedDatabase.flatMapLatest { it.documentDao().getDocumentById(id) }
        .combine(vaultReady) { row, ready -> if (ready && row != null) VaultRecords.decode(row, cipher) else null }

    fun updateConfig(newConfig: DesktopNodeConfig) {
        requireReady()
        if (!newConfig.simulatedNodeEnabled && newConfig.isPaired) PairingConfigParser.certificate(newConfig)
        else if (!newConfig.simulatedNodeEnabled) require(newConfig.hostIp.isBlank() && newConfig.pairKey.isBlank() && newConfig.certificateDer.isBlank()) { "Use the complete desktop pairing link." }
        if (newConfig.simulatedNodeEnabled && demoDatabase == null) throw IllegalStateException("Demo requires a separate workspace.")
        val generation = ++workspaceGeneration
        opening?.cancel(); nodeService.cancelAll(); activeRequests.keys.forEach { it.cancel() }
        if (!newConfig.simulatedNodeEnabled) configStore?.save(newConfig)
        _nodeConfig.value = newConfig
        _workflowState.value = WorkflowSnapshot(); _lastSyncTimestamp.value = 0
        _connectionStatus.value = ConnectionStatus.DISCONNECTED; _nodeStats.value = NodeStats()
        _vaultReady.value = false
        selectWorkspace()
        opening = scope.launch {
            try {
                selectedDatabase.value.migrateLegacyContent(cipher)
                ensureActive()
                check(generation == workspaceGeneration)
                _vaultReady.value = true
                if (newConfig.simulatedNodeEnabled) seedDemo()
                checkConnection()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == workspaceGeneration) { _vaultReady.value = false; _lastError.value = "The selected protected workspace could not be opened. No data was erased." }
            }
        }
    }

    fun toggleSimulatedDesktopNode(enabled: Boolean) = updateConfig(_nodeConfig.value.copy(simulatedNodeEnabled = enabled))
    fun pair(link: String) = updateConfig(PairingConfigParser.parse(link, _nodeConfig.value))

    suspend fun checkConnection(): Boolean {
        requireReady()
        val config = _nodeConfig.value
        val generation = workspaceGeneration
        _connectionStatus.value = ConnectionStatus.CONNECTING
        return try {
            val stats = nodeService.getNodeStats(config)
            requireCurrent(config, generation)
            _nodeStats.value = stats
            _modelProcessingMode.value = stats.processingMode
            _connectionStatus.value = if (config.simulatedNodeEnabled) ConnectionStatus.DEMO else ConnectionStatus.CONNECTED
            _lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _nodeStats.value = NodeStats()
            _lastError.value = safeMessage(e)
            false
        }
    }

    suspend fun triggerSync(): SyncResult = withTrackedRequest {
        requireReady()
        if (!sync.tryLock()) return@withTrackedRequest SyncResult(false, errorMessage = "A desktop check is already in progress.")
        val config = _nodeConfig.value
        val generation = workspaceGeneration
        _isSyncing.value = true
        try {
            if (!checkConnection()) return@withTrackedRequest SyncResult(false, errorMessage = _lastError.value)
            requireCurrent(config, generation)
            var count = 0
            val queued = chatDao.getQueuedOfflineMessages().map { VaultRecords.decode(it, cipher) }
            val deliveryErrors = mutableListOf<String>()
            for (message in queued) {
                requireCurrent(config, generation)
                try { deliver(message); count++ }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    requireCurrent(config, generation)
                    deliveryErrors.add(safeMessage(e))
                }
            }
            count += replayCaptures(config, generation, deliveryErrors)
            val stats = _nodeStats.value
            val update = nodeService.pullDesktopSync(config, stats)
            requireCurrent(config, generation)
            taskDao.insertTasks(update.updatedTasks.map { VaultRecords.encode(it, cipher) })
            requireCurrent(config, generation)
            taskDao.mergeNotificationsPreservingLocalState(update.newNotifications.map { VaultRecords.encode(it, cipher) })
            requireCurrent(config, generation)
            count += update.updatedTasks.size + update.newNotifications.size
            if (!config.simulatedNodeEnabled) {
                // Newer desktops advertise shared conversations; old gateways still support text sessions.
                if ("chat" in stats.scopes && "conversations" in stats.capabilities) count += syncConversations(config, generation)
                val snapshot = workflows.snapshot(config, stats.scopes)
                requireCurrent(config, generation)
                _workflowState.value = snapshot
            }
            _lastSyncTimestamp.value = System.currentTimeMillis()
            if (deliveryErrors.isNotEmpty()) {
                _lastError.value = "Desktop records refreshed; some requests remain pending or need review. " + deliveryErrors.distinct().first()
                return@withTrackedRequest SyncResult(false, count, _lastSyncTimestamp.value, _lastError.value)
            }
            _lastError.value = null
            SyncResult(true, count, _lastSyncTimestamp.value)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _lastError.value = safeMessage(e)
            SyncResult(false, errorMessage = _lastError.value)
        } finally { _isSyncing.value = false; sync.unlock() }
    }

    suspend fun createNewSession(title: String, initialPrompt: String? = null): String {
        requireReady()
        val id = UUID.randomUUID().toString()
        chatDao.insertSession(VaultRecords.encode(EncryptedChatSession(id, title.take(160), isSynced = false, modelUsed = "Unknown"), cipher))
        if (!initialPrompt.isNullOrBlank()) sendMessage(id, initialPrompt)
        return id
    }

    suspend fun sendMessage(sessionId: String, prompt: String, attachment: ChatAttachment? = null) {
        requireReady()
        if (attachment != null) {
            createCapture(prompt.ifBlank { "Evidence supplied for human review." }, "note", attachment = attachment)
            return
        }
        val id = UUID.randomUUID().toString()
        DesktopNodeService.chatRequest(sessionId,prompt,id,_secureClinicalMode.value,_secureClinicalMode.value)
        val message = EncryptedChatMessage(id, sessionId, "user", cipher.encrypt(prompt),
            status = "QUEUED_OFFLINE", modelName = "Unknown", desktopFingerprint = PairingConfigParser.destinationIdentity(_nodeConfig.value).takeIf { it.isNotBlank() },
            requireLocal = _secureClinicalMode.value, requireHipaa = _secureClinicalMode.value)
        chatDao.insertMessage(VaultRecords.encode(message, cipher))
        val session = getSessionById(sessionId).first()
        if (session != null) chatDao.insertSession(VaultRecords.encode(session.copy(lastMessagePreviewEncrypted = cipher.encrypt(prompt), lastMessageTimestamp = message.timestamp), cipher))
        if (_connectionStatus.value in setOf(ConnectionStatus.CONNECTED, ConnectionStatus.DEMO)) deliver(message)
    }

    private suspend fun deliver(message: EncryptedChatMessage) = withTrackedRequest { delivery.withLock {
        requireReady()
        val destination = _nodeConfig.value
        val generation = workspaceGeneration
        val targetDao = chatDao
        var attempt = message
        try {
            val expected = PairingConfigParser.destinationIdentity(destination)
            if (expected.isBlank() || message.desktopFingerprint != expected) throw GatewayException("PAIRING_CHANGED", 403,
                "This pending message belongs to another or unknown desktop pairing. Review it before submitting a new request.")
            if (_secureClinicalMode.value && (!message.requireLocal || !message.requireHipaa)) {
                if (message.attemptedAt != null) throw GatewayException("PRIVACY_CHANGED", 403,
                    "Privacy requirements changed after this request was attempted. Review its desktop history and submit a new request if appropriate.")
                attempt = attempt.copy(requireLocal = true, requireHipaa = true)
            }
            // Persist both the immutable request policy and its first attempt before any network work.
            attempt = attempt.copy(attemptedAt = attempt.attemptedAt ?: System.currentTimeMillis())
            targetDao.updateMessage(VaultRecords.encode(attempt, cipher))
            val reply = nodeService.dispatchInferenceToDesktop(attempt.sessionId, cipher.decrypt(attempt.encryptedContent),
                destination, attempt.requestId, requireLocal = attempt.requireLocal, requireHipaa = attempt.requireHipaa)
            requireCurrent(destination, generation)
            val result = EncryptedChatMessage("reply-${attempt.id}", attempt.sessionId, "assistant",
                cipher.encrypt(reply.reply + if (reply.incomplete) "\n\nResponse reached the desktop output limit." else ""),
                status = "SYNCED_DESKTOP", modelName = reply.model ?: "Not provided")
            targetDao.acknowledgeMessageWithReply(attempt.id, VaultRecords.encode(result, cipher))
            val session = getSessionById(attempt.sessionId).first()
            requireCurrent(destination, generation)
            if (session != null) targetDao.insertSession(VaultRecords.encode(session.copy(isSynced = true,
                modelUsed = reply.model ?: "Not provided", lastMessageTimestamp = result.timestamp), cipher))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            requireCurrent(destination, generation)
            targetDao.updateMessage(VaultRecords.encode(attempt.copy(status = if (needsReview(e)) "REVIEW_REQUIRED" else "QUEUED_OFFLINE",
                deliveryError = safeMessage(e)), cipher))
            _lastError.value = safeMessage(e)
            throw e
        }
    } }

    private fun needsReview(e: Exception) = e is GatewayException &&
        (e.code in setOf("INTERRUPTED", "CHANGED", "REVIEW_CHANGED", "COMPANION_ATTACHMENT", "UNSUPPORTED", "PROTOCOL") ||
            e.httpStatus in setOf(400,403,404,409,422))

    private suspend fun syncConversations(config: DesktopNodeConfig, generation: Long): Int {
        var count = 0
        for (row in workflows.conversations(config)) {
            requireCurrent(config, generation)
            val id = row.getString("sessionId")
            val history = workflows.history(config, id)
            requireCurrent(config, generation)
            val updated = row.optLong("updated")
            chatDao.insertSession(VaultRecords.encode(EncryptedChatSession(id = id, title = row.optString("title", "Desktop conversation"),
                createdAt = updated, lastMessageTimestamp = updated, modelUsed = "Desktop history", isSynced = true,
                desktopChatId = id, desktopRevision = updated), cipher))
            requireCurrent(config, generation)
            val array = history.getJSONArray("messages")
            val records = (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                VaultRecords.encode(EncryptedChatMessage(id = "desktop-$id-${m.getString("id")}", sessionId = id, sender = m.getString("role"),
                    encryptedContent = cipher.encrypt(m.optString("text")), timestamp = m.optLong("created"), status = "SYNCED_DESKTOP",
                    modelName = m.optJSONObject("processing")?.optString("model") ?: "Not provided", desktopMessageId = m.getString("id")), cipher)
            }
            chatDao.replaceSyncedHistory(id, records)
            count++
        }
        return count
    }

    suspend fun createCapture(text: String, kind: String = "note", location: String = "", owner: String = "", dueAt: Long? = null,
        draftChannel: String? = null, attachment: ChatAttachment? = null): String = withTrackedRequest {
        requireReady()
        val destination = _nodeConfig.value
        val generation = workspaceGeneration
        require(!destination.simulatedNodeEnabled) { "Operational captures are unavailable in Demo. Return to the real workspace." }
        require(text.isNotBlank() && text.length <= 12000 && kind in setOf("note","round","reminder"))
        require(location.length <= 160 && owner.length <= 100 && (dueAt == null || dueAt > 0))
        val sourceId = UUID.randomUUID().toString()
        val data = JSONObject().put("sourceId", sourceId).put("text", text).put("kind", kind).put("location", location).put("owner", owner)
            .put("dueAt", dueAt ?: JSONObject.NULL).put("observedAt", System.currentTimeMillis()).put("desktopFingerprint", PairingConfigParser.destinationIdentity(destination))
        if (draftChannel != null) { require(draftChannel in setOf("outlook","whatsapp")); data.put("draftChannel", draftChannel) }
        if (attachment != null) data.put("localEvidence", JSONObject().put("name", attachment.name).put("type", attachment.type).put("data", attachment.base64Data))
        saveLocalDocument(sourceId, "$kind — pending desktop review", "CAPTURE_PENDING", data.toString(), "Pending capture")
        requireCurrent(destination, generation)
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            try { requireCurrent(destination, generation); replayCaptures(destination, generation); refreshWorkflowSnapshot(destination, generation) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                // The local commit succeeded. Never report a metadata/network failure as a lost note.
                _lastError.value = "Note saved on this phone; desktop refresh is incomplete. " + safeMessage(e)
            }
        }
        sourceId
    }

    private suspend fun replayCaptures(destination: DesktopNodeConfig, generation: Long, errors: MutableList<String> = mutableListOf()): Int {
        requireCurrent(destination, generation)
        if (destination.simulatedNodeEnabled) return 0
        val pending = documents.first().filter { it.fileType == "CAPTURE_PENDING" }
        if (pending.isEmpty()) return 0
        if ("workflow" !in _nodeStats.value.scopes) {
            val message = "Enable this phone's Workflow permission on the desktop to submit pending notes."
            errors.add(message); _lastError.value = message
            return 0
        }
        var count = 0
        for (doc in pending) {
            requireCurrent(destination, generation)
            try {
                val payload = JSONObject(cipher.decrypt(doc.encryptedContent))
                val expected = PairingConfigParser.destinationIdentity(destination)
                if (expected.isBlank() || payload.optString("desktopFingerprint") != expected) throw GatewayException("PAIRING_CHANGED", 403,
                    "A pending note belongs to another or unknown desktop pairing. Review it before submitting a new note.")
                val local = payload.optJSONObject("localEvidence")
                if (local != null) {
                    val evidence = workflows.uploadEvidence(destination, doc.id,
                        ChatAttachment(local.getString("name"), local.getString("type"), local.getString("data"), 0L))
                    requireCurrent(destination, generation)
                    payload.remove("localEvidence"); payload.put("evidenceIds", JSONArray().put(evidence))
                    saveLocalDocument(doc.id, doc.fileName, "CAPTURE_PENDING", payload.toString(), doc.tags)
                }
                val request = JSONObject(payload.toString()).apply { remove("desktopFingerprint") }
                val receipt = workflows.createCapture(destination, request)
                requireCurrent(destination, generation)
                if (receipt.getJSONObject("capture").getString("sourceId") != doc.id) throw GatewayException("PROTOCOL", message = "Capture receipt did not match.")
                saveLocalDocument(doc.id, doc.fileName.replace("pending", "saved"), "CAPTURE_RECEIPT", receipt.toString(), "Saved on desktop; review required")
                count++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                requireCurrent(destination, generation)
                val message = safeMessage(e)
                if (needsReview(e)) saveLocalDocument(doc.id, doc.fileName, "CAPTURE_REVIEW_REQUIRED",
                    cipher.decrypt(doc.encryptedContent), "Review before resubmitting; " + message)
                errors.add(message); _lastError.value = message
            }
        }
        return count
    }

    private suspend fun refreshWorkflowSnapshot(config: DesktopNodeConfig, generation: Long) {
        requireCurrent(config, generation)
        val snapshot = workflows.snapshot(config, _nodeStats.value.scopes)
        requireCurrent(config, generation)
        _workflowState.value = snapshot
    }

    suspend fun updateAction(action: WorkflowAction, owner: String, dueAt: Long?, note: String, complete: Boolean) = withTrackedRequest {
        requireReady(); require(!complete || note.isNotBlank()) { "Enter a completion note." }
        val config = _nodeConfig.value; val generation = workspaceGeneration
        workflows.updateAction(config, action, owner, dueAt, note, complete)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }
    suspend fun saveDraft(draft: WorkflowDraft, recipient: String, body: String, subject: String) = withTrackedRequest {
        requireReady()
        val config = _nodeConfig.value; val generation = workspaceGeneration
        workflows.saveDraft(config, draft, recipient, body, subject)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }
    suspend fun acknowledgeFollowUp(id: String) = withTrackedRequest {
        requireReady()
        val config = _nodeConfig.value; val generation = workspaceGeneration
        workflows.acknowledgeNotification(config, id)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }

    suspend fun saveDocument(fileName: String, fileType: String, content: String, tags: String) {
        requireReady()
        saveLocalDocument(UUID.randomUUID().toString(), fileName, fileType, content, tags)
    }
    private suspend fun saveLocalDocument(id: String, name: String, type: String, content: String, tags: String) {
        requireReady()
        documentDao.insertDocument(VaultRecords.encode(EncryptedDocument(id, name.take(160), type, cipher.encrypt(content),
            EncryptionManager.computeSha256(content), content.toByteArray().size.toLong(), syncedWithDesktop = type == "CAPTURE_RECEIPT", tags = tags), cipher))
    }
    suspend fun deleteDocument(id: String) { requireReady(); documentDao.deleteDocument(id) }
    suspend fun deleteSession(id: String) { requireReady(); chatDao.deleteMessagesForSession(id); chatDao.deleteSession(id) }
    suspend fun markNotificationRead(id: String) { requireReady(); taskDao.markNotificationRead(id) }
    suspend fun markAllNotificationsRead() { requireReady(); taskDao.markAllNotificationsRead() }
    suspend fun acknowledgeAlert(id: String) { requireReady(); taskDao.acknowledgeNotification(id) } // Local read marker; never clinical closure.
    fun setSecureClinicalMode(enabled: Boolean) { _secureClinicalMode.value = enabled }
    fun setModelProcessingMode(mode: String) { DesktopNodeService.unsupported("Changing desktop processing policy from the phone") }
    suspend fun startAgent(name: String) { requireReady(); taskDao.insertTask(VaultRecords.encode(nodeService.startAgent(name, _nodeConfig.value), cipher)) }
    suspend fun runPlaybook(name: String) { requireReady(); taskDao.insertTask(VaultRecords.encode(nodeService.runPlaybook(name, _nodeConfig.value), cipher)) }
    suspend fun cancelTask(id: String) { requireReady(); nodeService.cancelTask(id, _nodeConfig.value); taskDao.updateTaskStatus(id, "CANCELLED") }
    suspend fun retryTask(id: String) { requireReady(); nodeService.retryTask(id, _nodeConfig.value); taskDao.updateTaskStatus(id, "QUEUED") }
    suspend fun submitApproval(id: String, approved: Boolean) { requireReady(); nodeService.submitApproval(id, approved, _nodeConfig.value); taskDao.updateTaskStatus(id, if (approved) "RUNNING" else "REJECTED") }
    fun stopStreaming() { activeRequests.keys.forEach { it.cancel() }; nodeService.cancelAll() }
    fun close() { onLocked(); scope.cancel() }

    private suspend fun seedDemo() {
        if (chatDao.getAllSessions().first().isNotEmpty()) return
        val id = UUID.randomUUID().toString()
        chatDao.insertSession(VaultRecords.encode(EncryptedChatSession(id, "DEMO — isolated example", modelUsed = "Demo", isSynced = false), cipher))
        chatDao.insertMessage(VaultRecords.encode(EncryptedChatMessage(UUID.randomUUID().toString(), id, "assistant",
            cipher.encrypt("DEMO — This is a separate example workspace. No desktop work, clinical finding or message delivery occurred."), status = "DEMO", modelName = "Demo"), cipher))
    }
    private fun safeMessage(e: Exception) = if (e is GatewayException) e.message ?: "Desktop request failed." else "The request could not be saved or completed. No successful delivery was recorded."
}
