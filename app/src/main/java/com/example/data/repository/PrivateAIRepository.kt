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
    private val cipher: VaultCipher = EncryptionManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val selectedDatabase = MutableStateFlow(database)
    private val chatDao get() = selectedDatabase.value.chatDao()
    private val documentDao get() = selectedDatabase.value.documentDao()
    private val taskDao get() = selectedDatabase.value.taskDao()
    private val workflows = SharedWorkflowService(nodeService)
    private val meetingOutbox = MeetingOutbox(database, cipher)
    private val meetingDelivery = Mutex()
    private val pairing = Mutex()
    private val pairingCommitGuard = Any()
    private val pairingService = PairingBootstrapService()
    private val pairingJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val pairingCancellation = java.util.concurrent.atomic.AtomicLong()
    private val _phoneMeetings = MutableStateFlow<List<PhoneMeeting>>(emptyList())
    val phoneMeetings = _phoneMeetings.asStateFlow()
    private val _desktopMeetings = MutableStateFlow<List<DesktopMeeting>>(emptyList())
    val desktopMeetings = _desktopMeetings.asStateFlow()
    private val _meetingPending = MutableStateFlow(0)
    val meetingPending = _meetingPending.asStateFlow()
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
    private val _lastConnectionCheck = MutableStateFlow(0L)
    val lastConnectionCheck = _lastConnectionCheck.asStateFlow()
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

    fun onUnlocked(): Job = synchronized(pairingCommitGuard) {
        val generation = ++workspaceGeneration
        _vaultReady.value = false
        opening?.cancel()
        scope.launch {
        try {
            database.migrateLegacyContent(cipher)
            _nodeConfig.value = configStore?.load() ?: _nodeConfig.value
            _secureClinicalMode.value = _nodeConfig.value.secureClinicalMode
            selectWorkspace()
            _lastSyncTimestamp.value = configStore?.lastSync(_nodeConfig.value) ?: 0
            selectedDatabase.value.migrateLegacyContent(cipher)
            synchronized(pairingCommitGuard) {
                ensureActive()
                check(generation == workspaceGeneration)
                _vaultReady.value = true
            }
            if (_nodeConfig.value.simulatedNodeEnabled) seedDemo()
            _lastError.value = null
            restartPolling(generation)
            if (_nodeConfig.value.isPaired || _nodeConfig.value.simulatedNodeEnabled) checkConnection()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            synchronized(pairingCommitGuard) {
                if (generation == workspaceGeneration) {
                    _vaultReady.value = false
                    _lastError.value = "The protected workspace could not be opened. Unlock again or use the recovery instructions; no data was erased."
                }
            }
        }
        }.also { opening = it }
    }

    private fun restartPolling(generation: Long) {
        polling?.cancel()
        polling = null
        val interval = when (_nodeConfig.value.batteryMode) {
            BatteryOptimizationMode.REAL_TIME -> 30_000L
            BatteryOptimizationMode.SMART_ECO -> 300_000L
            BatteryOptimizationMode.MANUAL_ONLY -> return
        }
        polling = scope.launch {
            while (isActive && _vaultReady.value && generation == workspaceGeneration) {
                delay(interval)
                if (!_vaultReady.value || generation != workspaceGeneration) break
                // Stop cancels this check, while future checks remain enabled for this open workspace.
                coroutineScope { launch { triggerSync() }.join() }
            }
        }
    }

    fun onLocked() = synchronized(pairingCommitGuard) {
        // Cancellation can dispatch continuations immediately on another thread.
        // Publish revocation before any transport or job observes cancellation.
        _vaultReady.value = false
        ++workspaceGeneration
        cancelPairing()
        opening?.cancel(); polling?.cancel(); activeRequests.keys.forEach { it.cancel() }; nodeService.cancelAll()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _nodeStats.value = NodeStats()
        _workflowState.value = WorkflowSnapshot()
        _phoneMeetings.value = emptyList(); _desktopMeetings.value = emptyList(); _meetingPending.value = 0
        _lastSyncTimestamp.value = 0
        _lastConnectionCheck.value = 0
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

    fun updateConfig(newConfig: DesktopNodeConfig): Job = synchronized(pairingCommitGuard) {
        cancelPairing()
        requireReady()
        if (!newConfig.simulatedNodeEnabled && newConfig.isPaired) PairingConfigParser.certificate(newConfig)
        else if (!newConfig.simulatedNodeEnabled) require(newConfig.hostIp.isBlank() && newConfig.pairKey.isBlank() && newConfig.certificateDer.isBlank()) { "Use the complete desktop pairing link." }
        if (newConfig.simulatedNodeEnabled && demoDatabase == null) throw IllegalStateException("Demo requires a separate workspace.")
        if (!newConfig.simulatedNodeEnabled) configStore?.save(newConfig)
        val generation = ++workspaceGeneration
        opening?.cancel(); polling?.cancel(); nodeService.cancelAll(); activeRequests.keys.forEach { it.cancel() }
        _nodeConfig.value = newConfig
        _secureClinicalMode.value = newConfig.secureClinicalMode
        _workflowState.value = WorkflowSnapshot(); _lastSyncTimestamp.value = 0
        _phoneMeetings.value = emptyList(); _desktopMeetings.value = emptyList(); _meetingPending.value = 0
        _connectionStatus.value = ConnectionStatus.DISCONNECTED; _nodeStats.value = NodeStats()
        _vaultReady.value = false
        selectWorkspace()
        return@synchronized scope.launch {
            try {
                selectedDatabase.value.migrateLegacyContent(cipher)
                synchronized(pairingCommitGuard) {
                    ensureActive()
                    check(generation == workspaceGeneration)
                    _vaultReady.value = true
                }
                _lastSyncTimestamp.value = configStore?.lastSync(newConfig) ?: 0
                if (newConfig.simulatedNodeEnabled) seedDemo()
                restartPolling(generation)
                checkConnection()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(pairingCommitGuard) {
                    if (generation == workspaceGeneration) { _vaultReady.value = false; _lastError.value = "The selected protected workspace could not be opened. No data was erased." }
                }
            }
        }.also { opening = it }
    }

    fun toggleSimulatedDesktopNode(enabled: Boolean) = updateConfig(_nodeConfig.value.copy(simulatedNodeEnabled = enabled))
    fun pair(link: String) = updateConfig(PairingConfigParser.parse(link, _nodeConfig.value))

    fun cancelPairing() {
        pairingCancellation.incrementAndGet()
        pairingService.cancelAll()
        pairingJobs.forEach { it.cancel() }
    }

    /** Verify before replacing a pairing; no request queue or token persistence is involved. */
    suspend fun pairSecure(link: String): PairingCompletion = withContext(dispatcher) {
        val job = currentCoroutineContext()[Job]
        val cancelEpoch = pairingCancellation.get()
        if (job != null) pairingJobs.add(job)
        try { withTrackedRequest {
            pairing.withLock {
                try {
                    requireReady()
                    val previous = _nodeConfig.value
                    if (previous.simulatedNodeEnabled) throw GatewayException("DEMO", message = "Turn off Demo mode before connecting to a real PC.")
                    val generation = workspaceGeneration
                    val authEpoch = if (cipher === EncryptionManager) EncryptionManager.accessGeneration else null
                    val lease = nodeService.authorizationLease(previous)
                    val check = {
                        if (job?.isActive == false || pairingCancellation.get() != cancelEpoch) throw CancellationException("Pairing was cancelled.")
                        requireCurrent(previous, generation)
                        if (cipher === EncryptionManager && (!EncryptionManager.hasCurrentAccess() || EncryptionManager.accessGeneration != authEpoch))
                            throw GatewayException("PHONE_LOCKED", 423, "Unlock the protected phone workspace before pairing.")
                        nodeService.requireAuthorization(previous, lease)
                    }
                    check()
                    val bootstrap = PairingBootstrapParser.isBootstrap(link)
                    val exchanged = if (bootstrap) pairingService.exchange(PairingBootstrapParser.parse(link), previous, check) else null
                    val next = exchanged?.config ?: PairingConfigParser.parse(link, previous)
                    check()
                    val stats = pairingService.verifyStatus(next, check)
                    if (exchanged != null && stats.scopes != exchanged.scopes) throw GatewayException("PAIR_PROTOCOL", message = "The desktop's verified permissions changed during pairing. Generate a new code.")
                    currentCoroutineContext().ensureActive()
                    synchronized(pairingCommitGuard) {
                        check()
                        val storage = configStore ?: throw GatewayException("PAIR_STORAGE", message = "Encrypted pairing storage is unavailable. The previous pairing is unchanged.")
                        // Encryption and the AtomicFile write may take time. Both guarded
                        // checkpoints must remain in the original authorization epoch.
                        storage.save(next, beforeCommit = check)
                        val selectedGeneration = ++workspaceGeneration
                        opening?.cancel(); polling?.cancel(); nodeService.cancelAll()
                        activeRequests.keys.filter { it !== job }.forEach { it.cancel() }
                        _nodeConfig.value = next
                        _secureClinicalMode.value = next.secureClinicalMode
                        _workflowState.value = WorkflowSnapshot()
                        _phoneMeetings.value = emptyList(); _desktopMeetings.value = emptyList(); _meetingPending.value = 0
                        _lastSyncTimestamp.value = 0
                        _nodeStats.value = stats
                        _lastConnectionCheck.value = System.currentTimeMillis()
                        _modelProcessingMode.value = stats.processingMode
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _lastError.value = null
                        restartPolling(selectedGeneration)
                    }
                    PairingCompletion(stats.serverName, stats.scopes, exchanged?.sharedChats ?: emptyList(),
                        next.certificateFingerprint, exchanged?.notificationsWhileLocked ?: false, bootstrap)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is GatewayException) throw e
                    throw GatewayException("PAIR_FAILED", message = "Pairing could not be verified or saved. The previous pairing is unchanged. Generate a new code on the PC before trying again.")
                }
            }
        } } finally { if (job != null) pairingJobs.remove(job) }
    }

    suspend fun checkConnection(): Boolean {
        requireReady()
        val config = _nodeConfig.value
        val generation = workspaceGeneration
        _connectionStatus.value = ConnectionStatus.CONNECTING
        return try {
            val stats = nodeService.getNodeStats(config)
            requireCurrent(config, generation)
            _nodeStats.value = stats
            _lastConnectionCheck.value = System.currentTimeMillis()
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
            if (!config.simulatedNodeEnabled && "meetings" in _nodeStats.value.capabilities && "workflow" in _nodeStats.value.scopes) {
                try { replayMeetings(config, generation); refreshMeetings() }
                catch(e: Exception) { if(e is CancellationException) throw e; deliveryErrors.add(safeMessage(e)) }
            }
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
            if (deliveryErrors.isNotEmpty()) {
                _lastError.value = "Desktop records refreshed; some requests remain pending or need review. " + deliveryErrors.distinct().first()
                return@withTrackedRequest SyncResult(false, count, _lastSyncTimestamp.value, _lastError.value)
            }
            val syncedAt = System.currentTimeMillis()
            if(!config.simulatedNodeEnabled) configStore?.recordSync(config, syncedAt)
            requireCurrent(config, generation)
            _lastSyncTimestamp.value = syncedAt
            _lastError.value = null
            SyncResult(true, count, _lastSyncTimestamp.value)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _lastError.value = safeMessage(e)
            if(e is GatewayException && e.code in setOf("OFFLINE", "TLS", "PHONE_LOCKED")) _connectionStatus.value = ConnectionStatus.DISCONNECTED
            SyncResult(false, errorMessage = _lastError.value)
        } finally { _isSyncing.value = false; sync.unlock() }
    }

    private fun meetingDestination(): Pair<DesktopNodeConfig, Long> {
        requireReady()
        val config = _nodeConfig.value
        check(config.isPaired && !config.simulatedNodeEnabled) { "Pair with the desktop before recording." }
        return config to workspaceGeneration
    }
    private suspend fun loadPhoneMeetings(config: DesktopNodeConfig, generation: Long) {
        val binding = PairingConfigParser.destinationIdentity(config)
        val rows = meetingOutbox.list(binding)
        val count = database.pendingOperationDao().meetingCount(binding)
        requireCurrent(config, generation)
        _phoneMeetings.value = rows; _meetingPending.value = count
    }
    suspend fun createMeeting(title: String): String {
        val (config, generation) = meetingDestination()
        val id = meetingOutbox.create(PairingConfigParser.destinationIdentity(config), title)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
        return id
    }
    suspend fun saveMeetingAudio(id: String, wav: ByteArray) {
        val (config, generation) = meetingDestination()
        meetingOutbox.append(PairingConfigParser.destinationIdentity(config), id, wav)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
    }
    suspend fun finishMeeting(id: String) {
        val (config, generation) = meetingDestination()
        meetingOutbox.finish(PairingConfigParser.destinationIdentity(config), id)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
    }
    suspend fun refreshMeetings() = withTrackedRequest {
        val (config, generation) = meetingDestination()
        loadPhoneMeetings(config, generation)
        if ("meetings" in _nodeStats.value.capabilities && "workflow" in _nodeStats.value.scopes) {
            val result = workflows.meetings(config); requireCurrent(config, generation); _desktopMeetings.value = result
        }
    }
    private suspend fun replayMeetings(config: DesktopNodeConfig, generation: Long) = meetingDelivery.withLock {
        val dao = database.pendingOperationDao()
        val binding = PairingConfigParser.destinationIdentity(config)
        // Bound each refresh. Only one audio payload is decrypted at a time.
        repeat(30) {
            requireCurrent(config, generation)
            val row = dao.nextMeeting(binding) ?: return@withLock
            val data = JSONObject(cipher.decrypt(row.encryptedPayload))
            dao.attempted(row.id, System.currentTimeMillis()); requireCurrent(config, generation)
            val serverId = workflows.deliverMeeting(config, row.operation, data)
            requireCurrent(config, generation)
            dao.acknowledgeMeeting(row.id, serverId, cipher.encrypt(JSONObject().put("sourceId", data.getString("sourceId")).put("serverId", serverId).toString()))
        }
    }

    suspend fun createNewSession(title: String, initialPrompt: String? = null): String {
        requireReady()
        val id = UUID.randomUUID().toString()
        chatDao.insertSession(VaultRecords.encode(EncryptedChatSession(id, title.take(160), isSynced = false, modelUsed = "Unknown"), cipher))
        if (!initialPrompt.isNullOrBlank()) sendMessage(id, initialPrompt)
        return id
    }

    suspend fun sendMessage(sessionId: String, prompt: String, attachment: ChatAttachment? = null,
        onSaved: (ChatSubmissionReceipt) -> Unit = {}): ChatSubmissionReceipt = withTrackedRequest {
        requireReady()
        if (attachment != null) {
            val id = createCapture(prompt.ifBlank { "Evidence supplied for human review." }, "note", attachment = attachment,
                onLocalSave = { onSaved(ChatSubmissionReceipt(sessionId, it, true, "CAPTURE_PENDING")) })
            val status = documents.first().first { it.id == id }.fileType
            return@withTrackedRequest ChatSubmissionReceipt(sessionId, id, true, status)
        }
        sendTextMessage(sessionId, prompt, _secureClinicalMode.value, _secureClinicalMode.value, onSaved)
    }

    /** A new explicit request, available only for a completed phone request with retained policy/binding. */
    suspend fun askAgain(sessionId: String, originalMessageId: String,
        onSaved: (ChatSubmissionReceipt) -> Unit = {}): ChatSubmissionReceipt = withTrackedRequest {
        requireReady()
        val config = _nodeConfig.value
        val generation = workspaceGeneration
        val original = chatDao.messageById(originalMessageId)?.let { VaultRecords.decode(it, cipher) }
            ?: throw GatewayException("REVIEW_CHANGED", 409, "The original question is no longer available. Review the conversation before asking again.")
        if (original.sessionId != sessionId || original.sender != "user" || original.status != "SYNCED_DESKTOP" ||
            original.attachmentName != null || original.attachmentDataEncrypted != null) {
            throw GatewayException("REVIEW_CHANGED", 409, "Only a completed text question can be asked again. Review pending or uncertain delivery on the desktop.")
        }
        requireCurrent(config, generation)
        val destination = PairingConfigParser.destinationIdentity(config)
        if (destination.isBlank() || original.desktopFingerprint != destination) {
            throw GatewayException("PAIRING_CHANGED", 403, "The original question belongs to another or unknown desktop pairing. Review it before making a new request.")
        }
        sendTextMessage(sessionId, cipher.decrypt(original.encryptedContent),
            original.requireLocal || _secureClinicalMode.value, original.requireHipaa || _secureClinicalMode.value, onSaved)
    }

    private suspend fun sendTextMessage(sessionId: String, prompt: String, requireLocal: Boolean, requireHipaa: Boolean,
        onSaved: (ChatSubmissionReceipt) -> Unit): ChatSubmissionReceipt {
        requireReady()
        val destination = _nodeConfig.value
        val generation = workspaceGeneration
        val targetDao = chatDao
        val id = UUID.randomUUID().toString()
        DesktopNodeService.chatRequest(sessionId, prompt, id, requireLocal, requireHipaa)
        val message = EncryptedChatMessage(id, sessionId, "user", cipher.encrypt(prompt),
            status = "QUEUED_OFFLINE", modelName = "Unknown", desktopFingerprint = PairingConfigParser.destinationIdentity(destination).takeIf { it.isNotBlank() },
            requireLocal = requireLocal, requireHipaa = requireHipaa)
        requireCurrent(destination, generation)
        targetDao.insertMessage(VaultRecords.encode(message, cipher))
        requireCurrent(destination, generation)
        onSaved(ChatSubmissionReceipt(sessionId, id, false, message.status))
        val session = getSessionById(sessionId).first()
        requireCurrent(destination, generation)
        if (session != null) targetDao.insertSession(VaultRecords.encode(session.copy(lastMessagePreviewEncrypted = cipher.encrypt(prompt), lastMessageTimestamp = message.timestamp), cipher))
        if (_connectionStatus.value in setOf(ConnectionStatus.CONNECTED, ConnectionStatus.DEMO)) deliver(message)
        requireCurrent(destination, generation)
        return ChatSubmissionReceipt(sessionId, id, false, targetDao.messageById(id)?.status ?: message.status)
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
        draftChannel: String? = null, attachment: ChatAttachment? = null, onLocalSave: (String) -> Unit = {}): String = withTrackedRequest {
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
        onLocalSave(sourceId)
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
    fun setSecureClinicalMode(enabled: Boolean) = updateConfig(_nodeConfig.value.copy(secureClinicalMode = enabled))
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
