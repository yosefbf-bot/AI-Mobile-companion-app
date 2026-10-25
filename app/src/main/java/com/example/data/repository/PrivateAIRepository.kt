package com.example.data.repository

import com.example.data.crypto.EncryptionManager
import com.example.data.crypto.SecureConfigStore
import com.example.data.crypto.VaultUnavailableException
import com.example.data.crypto.VaultCipher
import com.example.data.crypto.VaultRecords
import com.example.data.crypto.EvidenceValidator
import com.example.data.crypto.EvidenceValidationException
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import com.example.data.model.*
import com.example.data.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remainingLeaseMillis: () -> Long = {
        if (cipher === EncryptionManager) EncryptionManager.remainingSessionMillis() else Long.MAX_VALUE
    }
) {
    private companion object {
        // Desktop request receipts expire after 24 hours. Leave a one-hour
        // margin and require review before replaying an uncertain old delivery.
        const val AUTOMATIC_CHAT_REPLAY_WINDOW_MILLIS = 23L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val selectedDatabase = MutableStateFlow(database)
    private val chatDao get() = selectedDatabase.value.chatDao()
    private val documentDao get() = selectedDatabase.value.documentDao()
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
    private val captureDelivery = Mutex()
    private val sync = Mutex()
    private var opening: Job? = null
    @Volatile private var workspaceGeneration = 0L
    private var polling: Job? = null
    private val pendingWake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var historiesPending = false
    private val activeRequests = java.util.concurrent.ConcurrentHashMap<Job, java.util.concurrent.atomic.AtomicInteger>()
    private val activeStorage = mutableSetOf<CompletableDeferred<Unit>>()
    private val localDrafts = LocalDraftStore(cipher, { route -> synchronized(pairingCommitGuard) {
        requireReady()
        require(route.isNotBlank() && route.length <= 512)
        val identity = PairingConfigParser.destinationIdentity(_nodeConfig.value)
        DraftScope(selectedDatabase.value, EncryptionManager.computeSha256(
            "PrivateAI.localDraft.v1|${_nodeConfig.value.simulatedNodeEnabled}|$identity|$route"), workspaceGeneration)
    } }, { token -> requireReady(); check(token.workspace == workspaceGeneration) { "The draft workspace changed. Unlock and reload it." } })
    val localDraftWriteVersion get() = localDrafts.writeVersion
    suspend fun loadLocalDraft(route: String) = localDrafts.load(route)
    fun currentLocalDraft(token: LocalDraftToken) = localDrafts.current(token)
    fun localDraftStatus(token: LocalDraftToken) = localDrafts.status(token)
    fun updateLocalDraft(token: LocalDraftToken, serializedForm: String, autoSave: Boolean) = localDrafts.update(token, serializedForm, autoSave)
    fun updateLocalDraftFields(token: LocalDraftToken, serializedFields: String, evidence: ChatAttachment?, autoSave: Boolean) = localDrafts.updateFields(token, serializedFields, evidence, autoSave)
    suspend fun flushLocalDrafts() = localDrafts.flush()
    fun prepareLocalDraftSubmission(token: LocalDraftToken, submittedForm: String, clearFields: Set<String>) = localDrafts.prepareSubmission(token, submittedForm, clearFields)
    suspend fun deleteLocalDraft(token: LocalDraftToken) = localDrafts.delete(token)
    fun acknowledgeLocalDraft(token: LocalDraftToken, submittedForm: String, reconcile: (String, String) -> String) = localDrafts.acknowledge(token, submittedForm, reconcile)
    fun acknowledgeLocalDraft(token: LocalDraftToken, submittedForm: String, draftSubmission: LocalDraftSubmission?, reconcile: (String, String) -> String) = localDrafts.acknowledge(token, submittedForm, reconcile, draftSubmission)
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
        .combine(vaultReady) { rows, ready -> visible(ready, emptyList()) { rows.map { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)
    val documents = selectedDatabase.flatMapLatest { it.documentDao().getAllDocuments() }
        .combine(vaultReady) { rows, ready -> visible(ready, emptyList()) { rows.map { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)
    val tasks = selectedDatabase.flatMapLatest { it.taskDao().getAllTasks() }
        .combine(vaultReady) { rows, ready -> visible(ready, emptyList()) { rows.map { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)
    val notifications = selectedDatabase.flatMapLatest { it.taskDao().getAllNotifications() }
        .combine(vaultReady) { rows, ready -> visible(ready, emptyList()) { rows.map { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)

    private val _alertCoverage = MutableStateFlow(AlertCoverage())
    val alertCoverage = _alertCoverage.asStateFlow()

    private val retiredOpenings = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    fun onUnlocked(): Job {
        var previousStorage: List<CompletableDeferred<Unit>> = emptyList()
        val openingState = synchronized(pairingCommitGuard) {
            val nextGeneration = ++workspaceGeneration
            _vaultReady.value = false
            previousStorage = activeStorage.toList()
            opening?.let { retiredOpenings.add(it) }
            Triple(nextGeneration, retiredOpenings.toList(), activeRequests.keys.toList()).also { opening = null }
        }
        val (generation, previousOpenings, previousRequests) = openingState
        previousOpenings.forEach { it.cancel() }
        previousRequests.forEach { it.cancel() }
        val next = scope.launch(start = CoroutineStart.LAZY) {
        try {
            // Join outside pairingCommitGuard: old finalizers need that monitor.
            previousOpenings.joinAll()
            retiredOpenings.removeAll(previousOpenings.toSet())
            previousRequests.joinAll()
            previousStorage.joinAll()
            localDrafts.flush()
            ensureActive()
            synchronized(pairingCommitGuard) { check(generation == workspaceGeneration) }
            val migrated = mutableSetOf<AppDatabase>()
            suspend fun migrateOnce(target: AppDatabase) {
                if (migrated.add(target)) target.migrateLegacyContent(cipher)
            }
            migrateOnce(database)
            if (cipher === EncryptionManager && EncryptionManager.hasPendingPolicyMigration()) {
                val demo = demoDatabase ?: throw VaultUnavailableException()
                migrateOnce(demo)
                EncryptionManager.completePendingPolicyMigration(database, demo, configStore, localDrafts::rewrapMemory)
            }
            localDrafts.reconcileAcknowledgments(listOfNotNull(database, demoDatabase))
            _nodeConfig.value = configStore?.load() ?: _nodeConfig.value
            _secureClinicalMode.value = _nodeConfig.value.secureClinicalMode
            selectWorkspace()
            _lastSyncTimestamp.value = configStore?.lastSync(_nodeConfig.value) ?: 0
            migrateOnce(selectedDatabase.value)
            synchronized(pairingCommitGuard) {
                ensureActive()
                check(generation == workspaceGeneration)
                _vaultReady.value = true
            }
            if (_nodeConfig.value.simulatedNodeEnabled) seedDemo()
            _lastError.value = null
            restartPolling(generation)
            if (_nodeConfig.value.isPaired || _nodeConfig.value.simulatedNodeEnabled) {
                if (_nodeConfig.value.batteryMode == BatteryOptimizationMode.MANUAL_ONLY) checkConnection()
                else triggerSync()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            synchronized(pairingCommitGuard) {
                if (generation == workspaceGeneration) {
                    _vaultReady.value = false
                    _lastError.value = "The protected workspace could not be opened. Unlock again or use the recovery instructions; no data was erased."
                }
            }
        }
        }
        synchronized(pairingCommitGuard) {
            if (generation == workspaceGeneration) opening = next else retiredOpenings.add(next)
        }
        if (generation != workspaceGeneration) next.cancel()
        next.start()
        return next
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
                val config = _nodeConfig.value
                val pending = historiesPending || chatDao.queuedCount() > 0 || documentDao.pendingCaptureIds().isNotEmpty() ||
                    (!config.simulatedNodeEnabled && database.pendingOperationDao().meetingCount(PairingConfigParser.destinationIdentity(config)) > 0)
                val wait = automaticSyncDelay(interval, pending, remainingLeaseMillis(), nodeService.retryDelayMillis()) ?: break
                // Newly saved pending work must not wait for the five-minute timer already armed.
                if (withTimeoutOrNull(wait) { pendingWake.receive(); true } == true) continue
                if (!_vaultReady.value || generation != workspaceGeneration || remainingLeaseMillis() <= 0) break
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
        localDrafts.onLocked()
        opening?.let { retiredOpenings.add(it) }; retiredOpenings.forEach { it.cancel() }
        polling?.cancel(); activeRequests.keys.forEach { it.cancel() }; nodeService.cancelAll()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _nodeStats.value = NodeStats()
        _workflowState.value = WorkflowSnapshot()
        _alertCoverage.value = AlertCoverage(); historiesPending = false
        _phoneMeetings.value = emptyList(); _desktopMeetings.value = emptyList(); _meetingPending.value = 0
        _lastSyncTimestamp.value = 0
        _lastConnectionCheck.value = 0
        _isSyncing.value = false
    }

    private fun requireReady() { check(_vaultReady.value) { "Unlock the protected phone workspace first." } }
    private data class WorkspaceBinding(val config: DesktopNodeConfig, val generation: Long, val database: AppDatabase)
    private fun captureWorkspace(): WorkspaceBinding = synchronized(pairingCommitGuard) {
        requireReady()
        WorkspaceBinding(_nodeConfig.value, workspaceGeneration, selectedDatabase.value)
    }
    private fun requireCurrent(workspace: WorkspaceBinding) = synchronized(pairingCommitGuard) {
        requireCurrent(workspace.config, workspace.generation)
        check(workspace.database === selectedDatabase.value) { "The protected database changed while this operation was waiting." }
    }
    private suspend fun <T> withTrackedRequest(expected: WorkspaceBinding? = null, block: suspend (WorkspaceBinding) -> T): T {
        val job = currentCoroutineContext()[Job]
        val workspace = synchronized(pairingCommitGuard) {
            val captured = expected ?: captureWorkspace()
            requireCurrent(captured)
            if (job != null) activeRequests.compute(job) { _, count -> (count ?: java.util.concurrent.atomic.AtomicInteger()).apply { incrementAndGet() } }
            captured
        }
        try { currentCoroutineContext().ensureActive(); return withContext(dispatcher) { requireCurrent(workspace); block(workspace) } }
        finally { if (job != null) activeRequests.computeIfPresent(job) { _, count -> if (count.decrementAndGet() == 0) null else count } }
    }
    private suspend fun <T> withTrackedStorage(block: suspend (WorkspaceBinding) -> T): T {
        val completion = CompletableDeferred<Unit>()
        val workspace = synchronized(pairingCommitGuard) { captureWorkspace().also { activeStorage.add(completion) } }
        // Reject work queued under an older pairing/unlock before admitting storage work.
        // Once admitted, a local commit can finish in its captured database; the next
        // unlock still joins activeStorage before adopting that committed work.
        try { currentCoroutineContext().ensureActive(); return withContext(dispatcher) { requireCurrent(workspace); block(workspace) } }
        finally { synchronized(pairingCommitGuard) { activeStorage.remove(completion); completion.complete(Unit) } }
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

    /** A lock or workspace switch during decoding must not emit an older workspace's content. */
    private fun <T> visible(ready: Boolean, empty: T, decode: () -> T): T {
        val generation = workspaceGeneration
        if (!ready || !_vaultReady.value) return empty
        return try { decode().takeIf { _vaultReady.value && generation == workspaceGeneration } ?: empty }
        catch (e: Exception) { if (!_vaultReady.value || generation != workspaceGeneration) empty else throw e }
    }

    fun getMessagesForSession(sessionId: String) = selectedDatabase.flatMapLatest { it.chatDao().getMessagesForSession(sessionId) }
        .combine(vaultReady) { rows, ready -> visible(ready, emptyList()) { rows.map { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)
    fun getSessionById(sessionId: String) = selectedDatabase.flatMapLatest { it.chatDao().getSessionById(sessionId) }
        .combine(vaultReady) { row, ready -> visible(ready, null) { row?.let { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)
    fun getDocumentById(id: String) = selectedDatabase.flatMapLatest { it.documentDao().getDocumentById(id) }
        .combine(vaultReady) { row, ready -> visible(ready, null) { row?.let { VaultRecords.decode(it, cipher) } } }
        .flowOn(dispatcher)

    suspend fun openDocument(id: String): Pair<EncryptedDocument, String>? = withTrackedStorage { workspace ->
        val row = workspace.database.documentDao().hydratedById(id)?.let { VaultRecords.decode(it, cipher) } ?: return@withTrackedStorage null
        val text = cipher.decrypt(row.encryptedContent)
        requireCurrent(workspace)
        row to text
    }

    suspend fun decryptDisplayText(payload: String): String = withTrackedStorage { workspace ->
        val text = cipher.decrypt(payload)
        requireCurrent(workspace)
        text
    }

    fun updateConfig(newConfig: DesktopNodeConfig): Job = synchronized(pairingCommitGuard) {
        cancelPairing()
        requireReady()
        if (!newConfig.simulatedNodeEnabled && newConfig.isPaired) PairingConfigParser.certificate(newConfig)
        else if (!newConfig.simulatedNodeEnabled) require(newConfig.hostIp.isBlank() && newConfig.pairKey.isBlank() && newConfig.certificateDer.isBlank()) { "Use the complete desktop pairing link." }
        if (newConfig.simulatedNodeEnabled && demoDatabase == null) throw IllegalStateException("Demo requires a separate workspace.")
        if (!newConfig.simulatedNodeEnabled) configStore?.save(newConfig)
        val generation = ++workspaceGeneration
        opening?.cancel(); polling?.cancel(); nodeService.cancelAll(); activeRequests.keys.forEach { it.cancel() }
        _alertCoverage.value = AlertCoverage(); historiesPending = false
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
    suspend fun pairSecure(link: String): PairingCompletion = coroutineScope {
        val job = currentCoroutineContext()[Job]
        val cancelEpoch = pairingCancellation.get()
        if (job != null) pairingJobs.add(job)
        try { withTrackedRequest { workspace ->
            pairing.withLock {
                try {
                    requireReady()
                    val previous = workspace.config
                    if (previous.simulatedNodeEnabled) throw GatewayException("DEMO", message = "Turn off Demo mode before connecting to a real PC.")
                    val generation = workspace.generation
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
                        _alertCoverage.value = AlertCoverage(scopeGranted = if ("alerts" in stats.scopes) null else false)
                        historiesPending = false
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

    suspend fun checkConnection(): Boolean = checkConnection(captureWorkspace())

    private suspend fun checkConnection(workspace: WorkspaceBinding): Boolean {
        requireCurrent(workspace)
        val config = workspace.config
        val generation = workspace.generation
        _connectionStatus.value = ConnectionStatus.CONNECTING
        return try {
            val stats = nodeService.getNodeStats(config)
            requireCurrent(config, generation)
            _nodeStats.value = stats
            if (!config.simulatedNodeEnabled && "alerts" !in stats.scopes) _alertCoverage.value = AlertCoverage(scopeGranted = false)
            _lastConnectionCheck.value = System.currentTimeMillis()
            _modelProcessingMode.value = stats.processingMode
            _connectionStatus.value = if (config.simulatedNodeEnabled) ConnectionStatus.DEMO else ConnectionStatus.CONNECTED
            _lastError.value = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            requireCurrent(workspace)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _nodeStats.value = NodeStats()
            _alertCoverage.value = AlertCoverage()
            _lastError.value = safeMessage(e)
            false
        }
    }

    suspend fun triggerSync(): SyncResult = withTrackedRequest { workspace ->
        requireReady()
        if (!sync.tryLock()) return@withTrackedRequest SyncResult(false, errorMessage = "A desktop check is already in progress.")
        val config = workspace.config
        val generation = workspace.generation
        val targetChatDao = workspace.database.chatDao()
        val targetTaskDao = workspace.database.taskDao()
        _isSyncing.value = true
        try {
            if (!checkConnection(workspace)) return@withTrackedRequest SyncResult(false, errorMessage = _lastError.value)
            requireCurrent(config, generation)
            var count = 0
            val queued = targetChatDao.getQueuedOfflineMessages().map { VaultRecords.decode(it, cipher) }
            val deliveryErrors = mutableListOf<String>()
            for (message in queued) {
                requireCurrent(config, generation)
                try { deliver(message, workspace); count++ }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    requireCurrent(config, generation)
                    deliveryErrors.add(safeMessage(e))
                    if (e is GatewayException && e.httpStatus == 429) throw e
                }
            }
            count += replayCaptures(workspace, deliveryErrors)
            if (!config.simulatedNodeEnabled) {
                loadPhoneMeetings(config, generation)
                if ("meetings" in _nodeStats.value.capabilities && "workflow" in _nodeStats.value.scopes) {
                    try { replayMeetings(config, generation, deliveryErrors); refreshMeetings() }
                    catch(e: Exception) {
                        if(e is CancellationException) throw e
                        if(e is GatewayException && e.httpStatus == 429) throw e
                        deliveryErrors.add(safeMessage(e))
                    }
                } else if (_meetingPending.value > 0) {
                    deliveryErrors.add("Meeting delivery is pending. Enable Workflow access for this phone and use a desktop with the meetings capability.")
                }
                loadPhoneMeetings(config, generation)
                if (_phoneMeetings.value.any { it.reviewError != null }) deliveryErrors.add("A meeting needs delivery review on the PC. Its phone audio is retained.")
            }
            val stats = _nodeStats.value
            val update = nodeService.pullDesktopSync(config, stats)
            requireCurrent(config, generation)
            targetTaskDao.insertTasks(update.updatedTasks.map { VaultRecords.encode(it, cipher) })
            requireCurrent(config, generation)
            targetTaskDao.mergeNotificationsPreservingLocalState(update.newNotifications.map { VaultRecords.encode(it, cipher) })
            requireCurrent(config, generation)
            _alertCoverage.value = update.alertCoverage
            count += update.updatedTasks.size + update.newNotifications.size
            if (!config.simulatedNodeEnabled) {
                // Newer desktops advertise shared conversations; old gateways still support text sessions.
                if ("chat" in stats.scopes && "conversations" in stats.capabilities) {
                    count += syncConversations(workspace)
                    if (historiesPending) deliveryErrors.add("More changed conversation histories remain to refresh.")
                }
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
            requireCurrent(workspace)
            _lastError.value = safeMessage(e)
            _alertCoverage.value = AlertCoverage(scopeGranted = if ("alerts" in _nodeStats.value.scopes) true else null)
            if(e is GatewayException && e.code in setOf("OFFLINE", "TLS", "PHONE_LOCKED")) _connectionStatus.value = ConnectionStatus.DISCONNECTED
            SyncResult(false, errorMessage = _lastError.value)
        } finally { _isSyncing.value = false; sync.unlock() }
    }

    private fun meetingDestination(workspace: WorkspaceBinding): Pair<DesktopNodeConfig, Long> {
        requireCurrent(workspace)
        val config = workspace.config
        check(config.isPaired && !config.simulatedNodeEnabled) { "Pair with the desktop before recording." }
        check(workspace.database === database)
        return config to workspace.generation
    }
    private suspend fun loadPhoneMeetings(config: DesktopNodeConfig, generation: Long) {
        val binding = PairingConfigParser.destinationIdentity(config)
        val rows = meetingOutbox.list(binding)
        val count = database.pendingOperationDao().meetingCount(binding)
        requireCurrent(config, generation)
        _phoneMeetings.value = rows; _meetingPending.value = count
    }
    suspend fun createMeeting(title: String): String = withTrackedStorage { workspace ->
        val (config, generation) = meetingDestination(workspace)
        val id = meetingOutbox.create(PairingConfigParser.destinationIdentity(config), title)
        pendingWake.trySend(Unit)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
        return@withTrackedStorage id
    }
    suspend fun saveMeetingAudio(id: String, wav: ByteArray) = withTrackedStorage { workspace ->
        val (config, generation) = meetingDestination(workspace)
        meetingOutbox.append(PairingConfigParser.destinationIdentity(config), id, wav)
        pendingWake.trySend(Unit)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
    }
    suspend fun finishMeeting(id: String) = withTrackedStorage { workspace ->
        val (config, generation) = meetingDestination(workspace)
        meetingOutbox.finish(PairingConfigParser.destinationIdentity(config), id)
        pendingWake.trySend(Unit)
        requireCurrent(config, generation); loadPhoneMeetings(config, generation)
    }
    suspend fun refreshMeetings() = withTrackedRequest { workspace ->
        val (config, generation) = meetingDestination(workspace)
        loadPhoneMeetings(config, generation)
        if ("meetings" in _nodeStats.value.capabilities && "workflow" in _nodeStats.value.scopes) {
            val result = workflows.meetings(config); requireCurrent(config, generation); _desktopMeetings.value = result
        }
    }
    private suspend fun replayMeetings(config: DesktopNodeConfig, generation: Long, errors: MutableList<String>) = meetingDelivery.withLock {
        val dao = database.pendingOperationDao()
        val binding = PairingConfigParser.destinationIdentity(config)
        // Bound each refresh. Only one audio payload is decrypted at a time.
        while (true) {
            repeat(30) {
                requireCurrent(config, generation)
                val row = dao.nextMeeting(binding) ?: return@withLock
                val data = JSONObject(cipher.decrypt(row.encryptedPayload))
                val sourceId = data.getString("sourceId")
                if (meetingOutbox.reviewError(binding, sourceId) != null) {
                    dao.reviewMeetingOperation(row.id)
                } else try {
                    dao.attempted(row.id, row.attemptedAt ?: System.currentTimeMillis()); requireCurrent(config, generation)
                    val serverId = workflows.deliverMeeting(config, row.operation, data)
                    requireCurrent(config, generation)
                    meetingOutbox.acknowledge(row, sourceId, serverId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    requireCurrent(config, generation)
                    if (e is GatewayException && e.code in setOf("NOT_FOUND", "CHANGED")) {
                        meetingOutbox.requireReview(row, sourceId, safeMessage(e))
                        errors.add(safeMessage(e))
                    } else throw e
                }
            }
            loadPhoneMeetings(config, generation)
            if (_meetingPending.value == 0) return@withLock
            // Continue another bounded pass while still authorized. The shared transport
            // also limits actual HTTP requests, including multipart audio, to 100/minute.
            delay(1_000)
            if (remainingLeaseMillis() <= 0) throw GatewayException("PHONE_LOCKED", 423, "Unlock to continue pending meeting delivery.")
        }
    }

    suspend fun createNewSession(title: String, initialPrompt: String? = null): String = withTrackedStorage { workspace ->
        val id = UUID.randomUUID().toString()
        workspace.database.chatDao().insertSession(VaultRecords.encode(EncryptedChatSession(id, title.take(160), isSynced = false, modelUsed = "Unknown"), cipher))
        if (!initialPrompt.isNullOrBlank()) withTrackedRequest(workspace) {
            sendTextMessage(workspace, id, initialPrompt, workspace.config.secureClinicalMode, workspace.config.secureClinicalMode, {})
        }
        return@withTrackedStorage id
    }

    private suspend fun <T> callerCallback(callback: (T) -> Unit): suspend (T) -> Unit {
        val caller = currentCoroutineContext()
        return { value -> withContext(caller) { callback(value) } }
    }

    suspend fun sendMessage(sessionId: String, prompt: String, attachment: ChatAttachment? = null,
        draftSubmission: LocalDraftSubmission? = null, onSaved: (ChatSubmissionReceipt) -> Unit = {}): ChatSubmissionReceipt = coroutineScope {
        val saved = callerCallback(onSaved)
        withTrackedRequest { workspace ->
        requireReady()
        if (attachment != null) {
            val id = createCaptureInWorkspace(workspace, prompt.ifBlank { "Evidence supplied for human review." }, "note", attachment = attachment,
                draftSubmission = draftSubmission, onLocalSave = { saved(ChatSubmissionReceipt(sessionId, it, true, "CAPTURE_PENDING")) })
            val status = workspace.database.documentDao().metadataById(id)?.fileType ?: error("The saved capture is no longer available.")
            requireCurrent(workspace)
            return@withTrackedRequest ChatSubmissionReceipt(sessionId, id, true, status)
        }
        sendTextMessage(workspace, sessionId, prompt, workspace.config.secureClinicalMode, workspace.config.secureClinicalMode, saved, draftSubmission)
        }
    }

    /** A new explicit request, available only for a completed phone request with retained policy/binding. */
    suspend fun askAgain(sessionId: String, originalMessageId: String,
        onSaved: (ChatSubmissionReceipt) -> Unit = {}): ChatSubmissionReceipt = coroutineScope {
        val saved = callerCallback(onSaved)
        withTrackedRequest { workspace ->
        requireReady()
        val config = workspace.config
        val generation = workspace.generation
        val original = workspace.database.chatDao().messageById(originalMessageId)?.let { VaultRecords.decode(it, cipher) }
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
        sendTextMessage(workspace, sessionId, cipher.decrypt(original.encryptedContent),
            original.requireLocal || config.secureClinicalMode, original.requireHipaa || config.secureClinicalMode, saved)
        }
    }

    private suspend fun sendTextMessage(workspace: WorkspaceBinding, sessionId: String, prompt: String, requireLocal: Boolean, requireHipaa: Boolean,
        onSaved: suspend (ChatSubmissionReceipt) -> Unit, draftSubmission: LocalDraftSubmission? = null): ChatSubmissionReceipt {
        requireCurrent(workspace)
        val destination = workspace.config
        val generation = workspace.generation
        val targetDatabase = workspace.database
        val targetDao = targetDatabase.chatDao()
        val id = UUID.randomUUID().toString()
        DesktopNodeService.chatRequest(sessionId, prompt, id, requireLocal, requireHipaa)
        val message = EncryptedChatMessage(id, sessionId, "user", cipher.encrypt(prompt),
            status = "QUEUED_OFFLINE", modelName = "Unknown", desktopFingerprint = PairingConfigParser.destinationIdentity(destination).takeIf { it.isNotBlank() },
            requireLocal = requireLocal, requireHipaa = requireHipaa)
        requireCurrent(destination, generation)
        val encodedMessage = VaultRecords.encode(message, cipher)
        DraftAcknowledgments.commit(targetDatabase, draftSubmission) { targetDao.insertMessage(encodedMessage) }
        pendingWake.trySend(Unit)
        requireCurrent(destination, generation)
        onSaved(ChatSubmissionReceipt(sessionId, id, false, message.status))
        requireCurrent(workspace)
        val session = targetDao.sessionById(sessionId)?.let { VaultRecords.decode(it, cipher) }
        requireCurrent(destination, generation)
        if (session != null) targetDao.insertSession(VaultRecords.encode(session.copy(lastMessagePreviewEncrypted = cipher.encrypt(prompt), lastMessageTimestamp = message.timestamp), cipher))
        if (_connectionStatus.value in setOf(ConnectionStatus.CONNECTED, ConnectionStatus.DEMO)) deliver(message, workspace)
        requireCurrent(destination, generation)
        return ChatSubmissionReceipt(sessionId, id, false, targetDao.messageById(id)?.status ?: message.status)
    }

    private suspend fun deliver(message: EncryptedChatMessage, workspace: WorkspaceBinding) = withTrackedRequest(workspace) { delivery.withLock {
        requireCurrent(workspace)
        val destination = workspace.config
        val generation = workspace.generation
        val targetDao = workspace.database.chatDao()
        // Another foreground pass may have delivered this row while waiting for the mutex.
        val current = targetDao.messageById(message.id)?.let { VaultRecords.decode(it, cipher) } ?: return@withLock
        if (current.status != "QUEUED_OFFLINE") return@withLock
        var attempt = current
        try {
            val expected = PairingConfigParser.destinationIdentity(destination)
            if (expected.isBlank() || current.desktopFingerprint != expected) throw GatewayException("PAIRING_CHANGED", 403,
                "This pending message belongs to another or unknown desktop pairing. Review it before submitting a new request.")
            if (_secureClinicalMode.value && (!current.requireLocal || !current.requireHipaa)) {
                if (current.attemptedAt != null) throw GatewayException("PRIVACY_CHANGED", 403,
                    "Privacy requirements changed after this request was attempted. Review its desktop history and submit a new request if appropriate.")
                attempt = attempt.copy(requireLocal = true, requireHipaa = true)
            }
            val now = System.currentTimeMillis()
            val firstAttempt = current.attemptedAt
            if (firstAttempt != null && (firstAttempt <= 0 || firstAttempt > now ||
                    now - firstAttempt >= AUTOMATIC_CHAT_REPLAY_WINDOW_MILLIS)) {
                throw GatewayException("REPLAY_WINDOW_EXPIRED", 409,
                    "This message may already have been processed. Review its desktop conversation before sending a new request.")
            }
            // Persist both the immutable request policy and its first attempt before any network work.
            attempt = attempt.copy(attemptedAt = firstAttempt ?: now)
            targetDao.updateMessage(VaultRecords.encode(attempt, cipher))
            val reply = nodeService.dispatchInferenceToDesktop(attempt.sessionId, cipher.decrypt(attempt.encryptedContent),
                destination, attempt.requestId, requireLocal = attempt.requireLocal, requireHipaa = attempt.requireHipaa)
            requireCurrent(destination, generation)
            val result = EncryptedChatMessage("reply-${attempt.id}", attempt.sessionId, "assistant",
                cipher.encrypt(reply.reply + if (reply.incomplete) "\n\nResponse reached the desktop output limit." else ""),
                status = "SYNCED_DESKTOP", modelName = reply.model ?: "Not provided")
            targetDao.acknowledgeMessageWithReply(attempt.id, VaultRecords.encode(result, cipher))
            val session = targetDao.sessionById(attempt.sessionId)?.let { VaultRecords.decode(it, cipher) }
            requireCurrent(destination, generation)
            if (session != null) targetDao.insertSession(VaultRecords.encode(session.copy(isSynced = true,
                modelUsed = reply.model ?: "Not provided", lastMessageTimestamp = result.timestamp), cipher))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            requireCurrent(destination, generation)
            targetDao.updateMessage(VaultRecords.encode(attempt.copy(status = if (needsReview(e)) "REVIEW_REQUIRED" else "QUEUED_OFFLINE",
                deliveryError = safeMessage(e)), cipher))
            pendingWake.trySend(Unit)
            _lastError.value = safeMessage(e)
            throw e
        }
    } }

    private fun needsReview(e: Exception) = e is EvidenceValidationException || e is GatewayException &&
        !(e.httpStatus == 409 && e.code in setOf("BUSY", "COMPANION_PAUSED")) &&
        (e.code in setOf("INTERRUPTED", "CHANGED", "REVIEW_CHANGED", "COMPANION_ATTACHMENT", "UNSUPPORTED", "PROTOCOL") ||
            e.httpStatus in setOf(400,403,404,409,422))

    private suspend fun syncConversations(workspace: WorkspaceBinding): Int {
        val config = workspace.config
        val generation = workspace.generation
        val targetDao = workspace.database.chatDao()
        var count = 0
        historiesPending = false
        for (row in workflows.conversations(config)) {
            requireCurrent(config, generation)
            val id = row.getString("sessionId")
            val revision = (row.opt("updated") as? Number)?.toLong()?.takeIf { it > 0 }
            val previous = targetDao.sessionById(id)
            if (revision != null && previous != null && previous.desktopChatId == id && previous.desktopRevision == revision) continue
            if (count >= 20) { historiesPending = true; continue }
            val history = workflows.history(config, id)
            requireCurrent(config, generation)
            require(history.getString("sessionId") == id) { "Conversation history did not match." }
            val updated = (history.opt("updated") as? Number)?.toLong()?.takeIf { it > 0 } ?: 0L
            val session = VaultRecords.encode(EncryptedChatSession(id = id, title = history.optString("title", row.optString("title", "Desktop conversation")),
                createdAt = previous?.createdAt ?: updated, lastMessageTimestamp = updated, modelUsed = "Desktop history", isSynced = true,
                desktopChatId = id, desktopRevision = updated), cipher)
            val array = history.getJSONArray("messages")
            val records = (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                VaultRecords.encode(EncryptedChatMessage(id = "desktop-$id-${m.getString("id")}", sessionId = id, sender = m.getString("role"),
                    encryptedContent = cipher.encrypt(m.optString("text")), timestamp = m.optLong("created"), status = "SYNCED_DESKTOP",
                    modelName = m.optJSONObject("processing")?.optString("model") ?: "Not provided", desktopMessageId = m.getString("id")), cipher)
            }
            requireCurrent(config, generation)
            targetDao.importHistory(session, records)
            count++
        }
        return count
    }

    suspend fun createCapture(text: String, kind: String = "note", location: String = "", owner: String = "", dueAt: Long? = null,
        draftChannel: String? = null, attachment: ChatAttachment? = null, draftSubmission: LocalDraftSubmission? = null, onLocalSave: suspend (String) -> Unit = {}): String = coroutineScope {
        val caller = currentCoroutineContext()
        withTrackedRequest { workspace ->
            createCaptureInWorkspace(workspace, text, kind, location, owner, dueAt, draftChannel, attachment, draftSubmission) {
                withContext(caller) { onLocalSave(it) }
            }
        }
    }

    private suspend fun createCaptureInWorkspace(workspace: WorkspaceBinding, text: String, kind: String = "note",
        location: String = "", owner: String = "", dueAt: Long? = null, draftChannel: String? = null,
        attachment: ChatAttachment? = null, draftSubmission: LocalDraftSubmission? = null,
        onLocalSave: suspend (String) -> Unit = {}): String {
        requireCurrent(workspace)
        val destination = workspace.config
        val generation = workspace.generation
        require(!destination.simulatedNodeEnabled) { "Operational captures are unavailable in Demo. Return to the real workspace." }
        require(text.isNotBlank() && text.length <= 12000 && kind in setOf("note","round","reminder"))
        require(location.length <= 160 && owner.length <= 100 && (dueAt == null || dueAt > 0))
        if (attachment != null) EvidenceValidator.validate(attachment)
        val sourceId = UUID.randomUUID().toString()
        val data = JSONObject().put("sourceId", sourceId).put("text", text).put("kind", kind).put("location", location).put("owner", owner)
            .put("dueAt", dueAt ?: JSONObject.NULL).put("observedAt", System.currentTimeMillis()).put("desktopFingerprint", PairingConfigParser.destinationIdentity(destination))
        if (draftChannel != null) { require(draftChannel in setOf("outlook","whatsapp")); data.put("draftChannel", draftChannel) }
        if (attachment != null) data.put("localEvidence", JSONObject().put("name", attachment.name).put("type", attachment.type).put("data", attachment.base64Data))
        saveLocalDocument(workspace, sourceId, "$kind — pending desktop review", "CAPTURE_PENDING", data.toString(), "Pending capture", draftSubmission)
        pendingWake.trySend(Unit)
        requireCurrent(destination, generation)
        onLocalSave(sourceId)
        requireCurrent(workspace)
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            try { requireCurrent(workspace); replayCaptures(workspace); refreshWorkflowSnapshot(destination, generation) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                requireCurrent(workspace)
                // The local commit succeeded. Never report a metadata/network failure as a lost note.
                _lastError.value = "Note saved on this phone; desktop refresh is incomplete. " + safeMessage(e)
            }
        }
        return sourceId
    }

    private suspend fun replayCaptures(workspace: WorkspaceBinding, errors: MutableList<String> = mutableListOf()): Int = captureDelivery.withLock {
        requireCurrent(workspace)
        val destination = workspace.config
        val generation = workspace.generation
        if (destination.simulatedNodeEnabled) return@withLock 0
        val targetDao = workspace.database.documentDao()
        val pending = targetDao.pendingCaptureIds()
        if (pending.isEmpty()) return@withLock 0
        if ("workflow" !in _nodeStats.value.scopes) {
            val message = "Enable this phone's Workflow permission on the desktop to submit pending notes."
            errors.add(message); _lastError.value = message
            return@withLock 0
        }
        var count = 0
        for (id in pending) {
            requireCurrent(destination, generation)
            val stored = targetDao.hydratedById(id) ?: continue
            // IDs are only a snapshot. Never interpret a receipt/review row as a pending envelope.
            if (stored.fileType != "CAPTURE_PENDING") continue
            val doc = VaultRecords.decode(stored, cipher)
            requireCurrent(workspace)
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
                    saveLocalDocument(workspace, doc.id, doc.fileName, "CAPTURE_PENDING", payload.toString(), doc.tags)
                }
                val request = JSONObject(payload.toString()).apply { remove("desktopFingerprint") }
                val receipt = workflows.createCapture(destination, request)
                requireCurrent(destination, generation)
                if (receipt.getJSONObject("capture").getString("sourceId") != doc.id) throw GatewayException("PROTOCOL", message = "Capture receipt did not match.")
                saveLocalDocument(workspace, doc.id, doc.fileName.replace("pending", "saved"), "CAPTURE_RECEIPT", receipt.toString(), "Saved on desktop; review required")
                count++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                requireCurrent(destination, generation)
                val message = safeMessage(e)
                if (needsReview(e)) saveLocalDocument(workspace, doc.id, doc.fileName, "CAPTURE_REVIEW_REQUIRED",
                    cipher.decrypt(doc.encryptedContent), "Review before resubmitting; " + message)
                errors.add(message); _lastError.value = message
                if (e is GatewayException && e.httpStatus == 429) throw e
            }
        }
        count
    }

    private suspend fun refreshWorkflowSnapshot(config: DesktopNodeConfig, generation: Long) {
        requireCurrent(config, generation)
        val snapshot = workflows.snapshot(config, _nodeStats.value.scopes)
        requireCurrent(config, generation)
        _workflowState.value = snapshot
    }

    suspend fun updateAction(action: WorkflowAction, owner: String, dueAt: Long?, note: String, complete: Boolean) = withTrackedRequest { workspace ->
        requireReady(); require(!complete || note.isNotBlank()) { "Enter a completion note." }
        val config = workspace.config; val generation = workspace.generation
        workflows.updateAction(config, action, owner, dueAt, note, complete)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }
    suspend fun saveDraft(draft: WorkflowDraft, recipient: String, body: String, subject: String) = withTrackedRequest { workspace ->
        requireReady()
        val config = workspace.config; val generation = workspace.generation
        workflows.saveDraft(config, draft, recipient, body, subject)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }
    suspend fun acknowledgeFollowUp(id: String) = withTrackedRequest { workspace ->
        requireReady()
        val config = workspace.config; val generation = workspace.generation
        workflows.acknowledgeNotification(config, id)
        requireCurrent(config, generation)
        refreshWorkflowSnapshot(config, generation)
    }

    suspend fun saveDocument(fileName: String, fileType: String, content: String, tags: String, draftSubmission: LocalDraftSubmission? = null) = withTrackedStorage { workspace ->
        saveLocalDocument(workspace, UUID.randomUUID().toString(), fileName, fileType, content, tags, draftSubmission)
    }
    private suspend fun saveLocalDocument(workspace: WorkspaceBinding, id: String, name: String, type: String, content: String, tags: String, draftSubmission: LocalDraftSubmission? = null) {
        requireCurrent(workspace)
        val target = workspace.database
        val encoded = VaultRecords.encode(EncryptedDocument(id, name.take(160), type, cipher.encrypt(content),
            EncryptionManager.computeSha256(content), content.toByteArray().size.toLong(), syncedWithDesktop = type == "CAPTURE_RECEIPT", tags = tags), cipher)
        DraftAcknowledgments.commit(target, draftSubmission) { target.documentDao().insertDocument(encoded) }
    }
    suspend fun deleteDocument(id: String) = withTrackedStorage { it.database.documentDao().deleteDocument(id) }
    suspend fun deleteSession(id: String) = withTrackedStorage { workspace ->
        val targetDao = workspace.database.chatDao()
        targetDao.deleteMessagesForSession(id); targetDao.deleteSession(id)
    }
    suspend fun markNotificationRead(id: String) = withTrackedStorage { it.database.taskDao().markNotificationRead(id) }
    suspend fun markAllNotificationsRead() = withTrackedStorage { it.database.taskDao().markAllNotificationsRead() }
    suspend fun acknowledgeAlert(id: String) = withTrackedStorage { it.database.taskDao().acknowledgeNotification(id) } // Local read marker; never clinical closure.
    fun setSecureClinicalMode(enabled: Boolean) = updateConfig(_nodeConfig.value.copy(secureClinicalMode = enabled))
    /** UI configuration writes include protected-file fsync and must run on the storage dispatcher. */
    suspend fun updateConfigAndWait(config: DesktopNodeConfig) {
        val workspace = captureWorkspace()
        withContext(dispatcher) {
            val (job, generation) = synchronized(pairingCommitGuard) {
                requireCurrent(workspace)
                updateConfig(config) to workspaceGeneration
            }
            job.join()
            requireCurrent(config, generation)
        }
    }
    fun setModelProcessingMode(mode: String) { DesktopNodeService.unsupported("Changing desktop processing policy from the phone") }
    suspend fun startAgent(name: String) = withTrackedRequest { workspace ->
        val task = nodeService.startAgent(name, workspace.config)
        requireCurrent(workspace)
        workspace.database.taskDao().insertTask(VaultRecords.encode(task, cipher))
    }
    suspend fun runPlaybook(name: String) = withTrackedRequest { workspace ->
        val task = nodeService.runPlaybook(name, workspace.config)
        requireCurrent(workspace)
        workspace.database.taskDao().insertTask(VaultRecords.encode(task, cipher))
    }
    suspend fun cancelTask(id: String) = withTrackedRequest { workspace ->
        nodeService.cancelTask(id, workspace.config); requireCurrent(workspace)
        workspace.database.taskDao().updateTaskStatus(id, "CANCELLED")
    }
    suspend fun retryTask(id: String) = withTrackedRequest { workspace ->
        nodeService.retryTask(id, workspace.config); requireCurrent(workspace)
        workspace.database.taskDao().updateTaskStatus(id, "QUEUED")
    }
    suspend fun submitApproval(id: String, approved: Boolean) = withTrackedRequest { workspace ->
        nodeService.submitApproval(id, approved, workspace.config); requireCurrent(workspace)
        workspace.database.taskDao().updateTaskStatus(id, if (approved) "RUNNING" else "REJECTED")
    }
    fun stopStreaming() { activeRequests.keys.forEach { it.cancel() }; nodeService.cancelAll() }
    fun close() { onLocked(); localDrafts.close(); scope.cancel() }

    private suspend fun seedDemo() {
        val workspace = captureWorkspace()
        check(workspace.config.simulatedNodeEnabled)
        val targetDao = workspace.database.chatDao()
        if (targetDao.getAllSessions().first().isNotEmpty()) return
        requireCurrent(workspace)
        val id = UUID.randomUUID().toString()
        targetDao.insertSession(VaultRecords.encode(EncryptedChatSession(id, "DEMO — isolated example", modelUsed = "Demo", isSynced = false), cipher))
        requireCurrent(workspace)
        targetDao.insertMessage(VaultRecords.encode(EncryptedChatMessage(UUID.randomUUID().toString(), id, "assistant",
            cipher.encrypt("DEMO — This is a separate example workspace. No desktop work, clinical finding or message delivery occurred."), status = "DEMO", modelName = "Demo"), cipher))
    }
    private fun safeMessage(e: Exception) = if (e is GatewayException || e is EvidenceValidationException) e.message ?: "Desktop request failed." else "The request could not be saved or completed. No successful delivery was recorded."
}
