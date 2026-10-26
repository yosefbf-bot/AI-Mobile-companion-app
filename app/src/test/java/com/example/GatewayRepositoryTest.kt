package com.example

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.repository.PrivateAIRepository
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.coroutines.CoroutineContext

/** Uses a clearly synthetic loopback TLS fixture, never a live account or workstation key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRepositoryTest {
    // Real loopback HTTP plus controlled coroutine schedulers: a missed dispatch
    // must fail this named test rather than wait for the outer CI job timeout.
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(120)

    private lateinit var server: HttpsServer
    private lateinit var config: DesktopNodeConfig
    private lateinit var database: AppDatabase
    private lateinit var demoDatabase: AppDatabase
    private lateinit var repository: PrivateAIRepository
    private lateinit var cipher: VaultCipher
    private lateinit var configuration: SecureConfigStore
    private val statusReads = AtomicInteger()
    private val chatRequests = AtomicInteger()
    private val evidenceRequests = AtomicInteger()
    private val actionReads = AtomicInteger()
    private val conversationReads = AtomicInteger()
    private val historyReads = AtomicInteger()
    private val meetingRequests = mutableListOf<Pair<String, JSONObject>>()
    private val captureRequests = mutableListOf<JSONObject>()
    private val requests = mutableListOf<JSONObject>()
    private val evidenceInputs = mutableListOf<JSONObject>()
    @Volatile private var allowChat = true
    @Volatile private var allowStatus = true
    @Volatile private var allowCapture = true
    @Volatile private var allowActionReads = true
    @Volatile private var grantChat = true
    @Volatile private var grantWorkflow = true
    @Volatile private var grantAlerts = false
    @Volatile private var meetingCapability = false
    @Volatile private var chatFailureCode: String? = null
    @Volatile private var historyFailureCode: String? = null
    @Volatile private var permanentlyFailedMeeting: String? = null
    @Volatile private var permanentMeetingCode = "NOT_FOUND"
    @Volatile private var conversationRows = org.json.JSONArray()
    @Volatile private var alertResponse = JSONObject().put("alerts", org.json.JSONArray())
    @Volatile private var wrongChatAcknowledgment = false
    @Volatile private var loseChatAcknowledgment = false
    @Volatile private var evidenceComplete = true
    @Volatile private var beforeEvidenceResponse: (() -> Unit)? = null
    @Volatile private var beforeStatusResponse: (() -> Unit)? = null
    @Volatile private var beforeCaptureResponse: ((JSONObject) -> Unit)? = null
    private val executor = Executors.newFixedThreadPool(2)

    @Before fun setup() {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/synthetic-gateway-test.p12")!!.use { store.load(it, "test-fixture".toCharArray()) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-fixture".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ssl)
        server.executor = executor
        val cert = store.getCertificate("synthetic").encoded
        // Most cases exercise explicit sync. Automatic scheduling has dedicated virtual-time cases.
        config = DesktopNodeConfig(hostIp = "127.0.0.1", port = server.address.port, pairKey = "A".repeat(43), batteryMode = BatteryOptimizationMode.MANUAL_ONLY,
            certificateDer = Base64.encodeToString(cert, Base64.NO_WRAP),
            certificateFingerprint = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it.toInt() and 255) })
        server.createContext("/mobile/api/v1/") { exchange ->
            val input = if (exchange.requestMethod == "POST") JSONObject(exchange.requestBody.bufferedReader().readText()) else JSONObject()
            var code = 200
            val output = when (exchange.requestURI.path) {
                GatewayEndpoints.STATUS -> {
                    statusReads.incrementAndGet()
                    beforeStatusResponse?.invoke()
                    if (!allowStatus) { code = 503; JSONObject().put("error",JSONObject().put("code","UNAVAILABLE")) } else JSONObject().put("status","ONLINE").put("protocolVersion",1)
                    .put("serverName","SYNTHETIC test server").put("loadedModel",JSONObject.NULL).put("localModelState","unloaded")
                    .put("cpuUsage",JSONObject.NULL).put("gpuUsage",JSONObject.NULL).put("vramUsedGb",JSONObject.NULL).put("vramTotalGb",JSONObject.NULL)
                    .put("activeJobs",0).put("scopes",JSONObject().put("chat",grantChat).put("tasks",true).put("workflow",grantWorkflow).put("alerts",grantAlerts))
                    .put("capabilities",org.json.JSONArray().put("conversations").apply { if (meetingCapability) put("meetings") })
                }
                GatewayEndpoints.CHAT_SEND -> {
                    chatRequests.incrementAndGet()
                    synchronized(requests) { requests.add(input) }
                    if (loseChatAcknowledgment) {
                        // The desktop received the request, but the phone gets no receipt.
                        exchange.close()
                        return@createContext
                    }
                    if (chatFailureCode != null) {
                        code = if (chatFailureCode == "RATE_LIMITED") 429 else 409
                        exchange.responseHeaders.add("Retry-After", "60")
                        JSONObject().put("error", JSONObject().put("code", chatFailureCode))
                    }
                    else if (!allowChat) { code = 503; JSONObject().put("error",JSONObject().put("code","LOCAL_UNAVAILABLE")) }
                    else JSONObject().put("reply","SYNTHETIC real HTTP reply").put("sessionId",input.getString("sessionId"))
                        .put("requestId",if (wrongChatAcknowledgment) "wrong-request" else input.getString("requestId")).put("processing",JSONObject().put("provider","local").put("model","SYNTHETIC test engine"))
                }
                GatewayEndpoints.TASKS -> JSONObject().put("tasks",org.json.JSONArray())
                GatewayEndpoints.ALERTS -> alertResponse
                "/mobile/api/v1/actions" -> {
                    actionReads.incrementAndGet()
                    if (!allowActionReads) { code = 503; JSONObject().put("error",JSONObject().put("code","BUSY")) }
                    else JSONObject().put("captures",org.json.JSONArray()).put("actions",org.json.JSONArray()).put("drafts",org.json.JSONArray())
                }
                "/mobile/api/v1/conversations" -> {
                    conversationReads.incrementAndGet()
                    if (!grantChat) { code = 403; JSONObject().put("error",JSONObject().put("code","POLICY")) }
                    else JSONObject().put("conversations",conversationRows)
                }
                "/mobile/api/v1/conversations/history" -> {
                    historyReads.incrementAndGet()
                    val row = (0 until conversationRows.length()).map { conversationRows.getJSONObject(it) }
                        .first { it.getString("sessionId") == input.getString("sessionId") }
                    if (historyFailureCode != null) { code = 503; JSONObject().put("error", JSONObject().put("code", historyFailureCode)) }
                    else JSONObject().put("sessionId", row.getString("sessionId")).put("title", row.getString("title"))
                        .put("updated", row.getLong("updated")).put("messages", org.json.JSONArray().put(JSONObject()
                            .put("id", "message").put("role", "assistant").put("text", "SYNTHETIC history").put("created", 1)))
                }
                "/mobile/api/v1/meetings" -> JSONObject().put("meetings", org.json.JSONArray())
                "/mobile/api/v1/meetings/create", "/mobile/api/v1/meetings/audio", "/mobile/api/v1/meetings/finish" -> {
                    synchronized(meetingRequests) { meetingRequests.add(exchange.requestURI.path to input) }
                    if (input.getString("sourceId") == permanentlyFailedMeeting && exchange.requestURI.path.endsWith("/audio")) {
                        code = if (permanentMeetingCode == "NOT_FOUND") 404 else 409
                        JSONObject().put("error", JSONObject().put("code", permanentMeetingCode))
                    } else if (exchange.requestURI.path.endsWith("/audio")) {
                        JSONObject().put("chunkId", input.getString("chunkId")).put("index", input.getInt("index"))
                            .put("complete", true).put("audioId", "synthetic-audio")
                            .put("sha256", input.getString("sha256")).put("bytes", input.getInt("bytes"))
                    } else JSONObject().put("sourceId", input.getString("sourceId")).put("id", "synthetic-meeting")
                }
                "/mobile/api/v1/actions/update" -> input.put("title","SYNTHETIC action").put("description","SYNTHETIC review").put("status","needs-review").put("unknownFields",org.json.JSONArray())
                "/mobile/api/v1/captures/create" -> {
                    synchronized(captureRequests) { captureRequests.add(input) }
                    beforeCaptureResponse?.invoke(input)
                    if (!allowCapture) { code = 503; JSONObject().put("error",JSONObject().put("code","BUSY")) }
                    else JSONObject().put("capture",JSONObject().put("id",input.getString("sourceId")).put("sourceId",input.getString("sourceId"))).put("replayed",false)
                }
                "/mobile/api/v1/evidence/write" -> {
                    evidenceRequests.incrementAndGet()
                    synchronized(evidenceInputs) { evidenceInputs.add(input) }
                    beforeEvidenceResponse?.invoke()
                    JSONObject().put("uploadId",input.getString("uploadId")).put("index",input.getInt("index"))
                        .put("complete",evidenceComplete).put("evidenceId","synthetic-evidence").put("bytes",input.getInt("bytes")).put("sha256",input.getString("sha256"))
                }
                else -> { code = 404; JSONObject().put("error",JSONObject().put("code","NOT_FOUND")) }
            }
            val bytes = output.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(code,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cipher = object : VaultCipher {
            override fun encrypt(plaintext: String) = VaultCodec.encrypt(plaintext, key)
            override fun decrypt(encryptedPayload: String) = VaultCodec.decrypt(encryptedPayload, key)
        }
        database = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        demoDatabase = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        configuration = SecureConfigStore(context,cipher)
        configuration.clear(); configuration.save(config)
        repository = PrivateAIRepository(database,configStore=configuration,demoDatabase=demoDatabase,cipher=cipher)
    }

    @After fun cleanup() {
        if (::repository.isInitialized) repository.close()
        if (::database.isInitialized) database.close()
        if (::demoDatabase.isInitialized) demoDatabase.close()
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun completeScheduledJob(scheduler: TestCoroutineScheduler, job: Job) {
        withTimeout(10_000) {
            while (!job.isCompleted) { scheduler.runCurrent(); delay(1) }
            scheduler.runCurrent()
        }
        job.join()
    }

    private suspend fun completeScheduledCheck(scheduler: TestCoroutineScheduler) {
        withTimeout(10_000) {
            scheduler.runCurrent()
            while (repository.isSyncing.value) { delay(1); scheduler.runCurrent() }
            // Also resume the polling parent's join, so the next interval is armed before advancing time.
            settlePolling(scheduler)
        }
    }

    /** Polling tests keep Room query dispatch inline so advancing the virtual clock cannot
     *  outrun the queries that arm the next interval. Network IO still completes normally. */
    private fun usePollingScheduler(scheduler: TestCoroutineScheduler, mode: BatteryOptimizationMode) {
        repository.close()
        database.close()
        demoDatabase.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun pollingDatabase() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { task -> task.run() }.build()
        database = pollingDatabase()
        demoDatabase = pollingDatabase()
        configuration.save(config.copy(batteryMode = mode))
        repository = PrivateAIRepository(database, configStore = configuration, demoDatabase = demoDatabase,
            cipher = cipher, dispatcher = StandardTestDispatcher(scheduler))
    }

    private suspend fun settlePolling(scheduler: TestCoroutineScheduler) {
        scheduler.runCurrent()
    }

    /** Hold exactly one dispatcher hop; other workspace changes can complete before it resumes. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        private val hold = AtomicBoolean(false)
        private val queued = LinkedBlockingQueue<Pair<CoroutineContext, Runnable>>()
        fun holdNext() { check(queued.isEmpty()); hold.set(true) }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold.compareAndSet(true, false)) queued.add(context to block)
            else Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            val task = queued.poll(10, TimeUnit.SECONDS)
            assertNotNull("Expected a retained dispatcher hop", task)
            task!!.let { (context, block) -> Dispatchers.IO.dispatch(context, block) }
        }
        fun releaseAll() {
            hold.set(false)
            while (true) {
                val (context, block) = queued.poll() ?: return
                Dispatchers.IO.dispatch(context, block)
            }
        }
    }

    @Test fun productionStartsEmptyAndRequiresUnlock() = runBlocking {
        assertFalse(DesktopNodeConfig().simulatedNodeEnabled)
        assertEquals(ConnectionStatus.DISCONNECTED,repository.connectionStatus.value)
        assertFalse(repository.vaultReady.value)
        repository.onUnlocked().join()
        assertTrue(repository.vaultReady.value)
        assertTrue(repository.chatSessions.first().isEmpty())
        assertTrue(repository.documents.first().isEmpty())
        assertTrue(repository.notifications.first().isEmpty())
        assertNull(repository.nodeStats.value.cpuUsagePercent)
        assertNull(repository.nodeStats.value.gpuUsagePercent)
        assertEquals(Integer.valueOf(0),repository.nodeStats.value.activeJobsCount)
        assertEquals("No local model loaded",repository.nodeStats.value.loadedModel)
        repository.onLocked()
        assertFalse(repository.vaultReady.value)
        assertTrue(repository.chatSessions.first().isEmpty())
    }

    @Test fun failedSendStaysPendingAndRetryUsesDurableRequestIdentity() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC note")
        allowChat = false
        try { repository.sendMessage(session,"SYNTHETIC inquiry"); fail("No successful answer may be invented.") } catch (_: GatewayException) {}
        val pending = database.chatDao().getQueuedOfflineMessages().single()
        assertEquals("QUEUED_OFFLINE",pending.status)
        assertEquals(PairingConfigParser.destinationIdentity(config),pending.desktopFingerprint)
        assertNotNull(pending.attemptedAt)
        assertTrue(pending.requireLocal)
        assertTrue(pending.requireHipaa)
        assertTrue(repository.getMessagesForSession(session).first().none { it.sender == "assistant" })
        repository.setSecureClinicalMode(false).join() // A later preference must never weaken the queued request.
        allowChat = true
        assertTrue(repository.triggerSync().success)
        assertTrue(database.chatDao().getQueuedOfflineMessages().isEmpty())
        val messages = repository.getMessagesForSession(session).first()
        assertEquals(2,messages.size)
        assertEquals("SYNTHETIC real HTTP reply",cipher.decrypt(messages.single { it.sender == "assistant" }.encryptedContent))
        assertEquals(requests[0].getString("requestId"),requests[1].getString("requestId"))
        assertTrue(requests[1].getBoolean("requireLocal"))
        assertTrue(requests[1].getBoolean("requireHipaa"))
        assertTrue(repository.triggerSync().success)
        assertEquals(2,chatRequests.get())
    }

    @Test fun stricterPrivacyAfterAnAttemptRequiresReviewAndDoesNotBlockWorkflowReads() = runBlocking {
        repository.onUnlocked().join()
        repository.setSecureClinicalMode(false).join()
        allowChat = false
        val session = repository.createNewSession("SYNTHETIC previous policy")
        try { repository.sendMessage(session,"SYNTHETIC request"); fail("Actual server failure must propagate.") } catch (_: GatewayException) {}
        assertFalse(requests.single().getBoolean("requireLocal"))
        repository.setSecureClinicalMode(true).join()
        allowChat = true
        val sync = repository.triggerSync()
        assertFalse(sync.success)
        assertTrue(sync.errorMessage!!.contains("refreshed"))
        assertEquals(1,chatRequests.get()) // No altered replay or cloud request is sent.
        val user = repository.getMessagesForSession(session).first().single()
        assertEquals("REVIEW_REQUIRED",user.status)
        assertNotNull(user.attemptedAt)
        assertFalse(user.requireLocal)
        assertTrue(actionReads.get() > 0)
        assertEquals(0L, repository.lastSyncTimestamp.value) // A partial refresh is not a successful sync.
        assertTrue(repository.lastConnectionCheck.value > 0)
    }

    @Test fun lostAcknowledgmentAfterReplayDeadlineRequiresReviewWithoutAnotherHttpRequest() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC uncertain delivery")
        loseChatAcknowledgment = true
        try { repository.sendMessage(session,"SYNTHETIC original question"); fail("A lost acknowledgment must remain uncertain.") }
        catch (_: GatewayException) {}
        val original = database.chatDao().getQueuedOfflineMessages().single()
        assertNotNull(original.attemptedAt)
        assertEquals(1, chatRequests.get())
        val oldAttempt = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        database.chatDao().updateMessage(original.copy(attemptedAt = oldAttempt))
        loseChatAcknowledgment = false
        val sync = repository.triggerSync()
        assertFalse(sync.success)
        assertEquals("No second HTTP request may restart expired desktop work.", 1, chatRequests.get())
        val reviewed = repository.getMessagesForSession(session).first().single()
        assertEquals("REVIEW_REQUIRED", reviewed.status)
        assertEquals(original.id, reviewed.id)
        assertEquals(original.requestId, reviewed.requestId)
        assertEquals(oldAttempt, reviewed.attemptedAt)
        assertEquals(original.desktopFingerprint, reviewed.desktopFingerprint)
        assertEquals(original.requireLocal, reviewed.requireLocal)
        assertEquals(original.requireHipaa, reviewed.requireHipaa)
        assertEquals("SYNTHETIC original question", cipher.decrypt(reviewed.encryptedContent))
        assertTrue(reviewed.deliveryError!!.contains("desktop conversation"))
        assertTrue(actionReads.get() > 0)
        assertTrue(database.chatDao().getQueuedOfflineMessages().isEmpty())
        repository.triggerSync()
        assertEquals("Automatic checks must not resubmit the reviewed request.", 1, chatRequests.get())
    }

    @Test fun lostAcknowledgmentInsideReplayWindowKeepsRequestPolicyAndFirstAttempt() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC recent delivery")
        loseChatAcknowledgment = true
        try { repository.sendMessage(session,"SYNTHETIC recent question"); fail("A lost acknowledgment must remain pending.") }
        catch (_: GatewayException) {}
        val original = database.chatDao().getQueuedOfflineMessages().single()
        val firstAttempt = System.currentTimeMillis() - 22L * 60 * 60 * 1000
        database.chatDao().updateMessage(original.copy(attemptedAt = firstAttempt))
        loseChatAcknowledgment = false
        assertTrue(repository.triggerSync().success)
        assertEquals(2, chatRequests.get())
        assertEquals(requests[0].getString("requestId"), requests[1].getString("requestId"))
        assertEquals(requests[0].getBoolean("requireLocal"), requests[1].getBoolean("requireLocal"))
        assertEquals(requests[0].getBoolean("requireHipaa"), requests[1].getBoolean("requireHipaa"))
        val stored = repository.getMessagesForSession(session).first().single { it.sender == "user" }
        assertEquals(firstAttempt, stored.attemptedAt)
        assertEquals("SYNCED_DESKTOP", stored.status)
        assertEquals(1, repository.getMessagesForSession(session).first().count { it.sender == "assistant" })
    }

    @Test fun neverAttemptedWeekOldOfflineChatStillGetsItsFirstDelivery() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC long offline queue")
        repository.sendMessage(session,"SYNTHETIC never attempted question")
        val original = database.chatDao().getQueuedOfflineMessages().single()
        assertNull(original.attemptedAt)
        database.chatDao().updateMessage(original.copy(timestamp = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
        allowStatus = true
        assertTrue(repository.triggerSync().success)
        assertEquals(1, chatRequests.get())
        assertEquals(original.requestId, requests.single().getString("requestId"))
        val stored = repository.getMessagesForSession(session).first().single { it.sender == "user" }
        assertNotNull(stored.attemptedAt)
        assertEquals("SYNCED_DESKTOP", stored.status)
    }

    @Test fun futureFirstAttemptRequiresReviewBeforeAnyReplay() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC changed clock")
        loseChatAcknowledgment = true
        try { repository.sendMessage(session,"SYNTHETIC clock question"); fail("A lost acknowledgment must remain pending.") }
        catch (_: GatewayException) {}
        val original = database.chatDao().getQueuedOfflineMessages().single()
        val futureAttempt = System.currentTimeMillis() + 60L * 60 * 1000
        database.chatDao().updateMessage(original.copy(attemptedAt = futureAttempt))
        loseChatAcknowledgment = false
        assertFalse(repository.triggerSync().success)
        assertEquals(1, chatRequests.get())
        val reviewed = repository.getMessagesForSession(session).first().single()
        assertEquals("REVIEW_REQUIRED", reviewed.status)
        assertEquals(original.requestId, reviewed.requestId)
        assertEquals(futureAttempt, reviewed.attemptedAt)
    }

    @Test fun neverAttemptedOfflineRequestCanAdoptStricterPrivacyBeforeItsFirstSend() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        repository.setSecureClinicalMode(false).join()
        val session = repository.createNewSession("SYNTHETIC offline")
        repository.sendMessage(session,"SYNTHETIC first attempt")
        val before = database.chatDao().getQueuedOfflineMessages().single()
        assertNull(before.attemptedAt)
        assertFalse(before.requireLocal)
        allowStatus = true
        repository.setSecureClinicalMode(true).join()
        assertTrue(repository.triggerSync().success)
        assertEquals(before.requestId,requests.single().getString("requestId"))
        assertTrue(requests.single().getBoolean("requireLocal"))
        assertTrue(requests.single().getBoolean("requireHipaa"))
    }

    @Test fun sameCertificateDifferentDevicePairingNeverReassignsPendingChatOrEvidence() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC old pairing")
        repository.sendMessage(session,"SYNTHETIC protected request")
        val captureId = repository.createCapture("SYNTHETIC protected observation")
        val replacement = config.copy(pairKey="B".repeat(43))
        assertNotEquals(PairingConfigParser.destinationIdentity(config),PairingConfigParser.destinationIdentity(replacement))
        allowStatus = true
        repository.updateConfig(replacement)
        repository.vaultReady.first { it }
        assertFalse(repository.triggerSync().success)
        assertEquals(0,chatRequests.get())
        assertTrue(captureRequests.isEmpty())
        assertEquals("REVIEW_REQUIRED",repository.getMessagesForSession(session).first().single().status)
        val preserved = repository.getDocumentById(captureId).first()!!
        assertEquals("CAPTURE_REVIEW_REQUIRED",preserved.fileType)
        assertTrue(cipher.decrypt(preserved.encryptedContent).contains("SYNTHETIC protected observation"))
    }

    @Test fun clinicalPrivacyPreferenceIsEncryptedAndRestoredAfterRepositoryRecreation() = runBlocking {
        repository.onUnlocked().join()
        repository.setSecureClinicalMode(false).join()
        assertFalse(repository.secureClinicalMode.value)
        assertFalse(configuration.load()!!.secureClinicalMode)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val encrypted = java.io.File(context.noBackupFilesDir, "private-ai-gateway-v2.bin").readText()
        assertTrue(encrypted.startsWith(VaultCodec.PREFIX))
        assertFalse(encrypted.contains("secureClinicalMode"))
        repository.close()
        repository = PrivateAIRepository(database, configStore = configuration, demoDatabase = demoDatabase, cipher = cipher)
        assertTrue(repository.secureClinicalMode.value) // Protected until the encrypted preference is opened.
        repository.onUnlocked().join()
        assertFalse(repository.secureClinicalMode.value)
        assertEquals(config.pairKey, repository.nodeConfig.value.pairKey)
        repository.setSecureClinicalMode(true).join()
        assertTrue(configuration.load()!!.secureClinicalMode)
    }

    @Test fun olderEncryptedConfigurationWithoutPrivacyFieldKeepsProtectedDefault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.noBackupFilesDir, "private-ai-gateway-v2.bin")
        val previous = JSONObject(cipher.decrypt(file.readText())).apply { remove("secureClinicalMode") }
        file.writeText(cipher.encrypt(previous.toString()))
        assertTrue(configuration.load()!!.secureClinicalMode)
        repository.onUnlocked().join()
        assertTrue(repository.secureClinicalMode.value)
        assertEquals(config.certificateFingerprint, repository.nodeConfig.value.certificateFingerprint)
    }

    @Test fun changingPollingModeRestartsItsIntervalAndManualLeavesNoPeriodicChecks() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        usePollingScheduler(scheduler, BatteryOptimizationMode.MANUAL_ONLY)
        completeScheduledJob(scheduler, repository.onUnlocked())
        completeScheduledJob(scheduler, repository.updateConfig(repository.nodeConfig.value.copy(batteryMode = BatteryOptimizationMode.REAL_TIME)))
        scheduler.advanceTimeBy(10_000); scheduler.runCurrent()
        completeScheduledJob(scheduler, repository.updateConfig(repository.nodeConfig.value.copy(batteryMode = BatteryOptimizationMode.SMART_ECO)))
        val balancedStart = statusReads.get()
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        assertEquals(balancedStart, statusReads.get()) // The old 30-second timer must be cancelled.
        scheduler.advanceTimeBy(269_999); scheduler.runCurrent()
        assertEquals(balancedStart, statusReads.get())
        scheduler.advanceTimeBy(1); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals(balancedStart + 1, statusReads.get())
        completeScheduledJob(scheduler, repository.updateConfig(repository.nodeConfig.value.copy(batteryMode = BatteryOptimizationMode.REAL_TIME)))
        val frequentStart = statusReads.get()
        scheduler.advanceTimeBy(29_999); scheduler.runCurrent()
        assertEquals(frequentStart, statusReads.get())
        scheduler.advanceTimeBy(1); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals(frequentStart + 1, statusReads.get())
        completeScheduledJob(scheduler, repository.updateConfig(repository.nodeConfig.value.copy(batteryMode = BatteryOptimizationMode.MANUAL_ONLY)))
        val manualStart = statusReads.get()
        scheduler.advanceTimeBy(600_000); scheduler.runCurrent()
        assertEquals(manualStart, statusReads.get())
        // triggerSync now dispatches storage/network work onto the repository's
        // dispatcher. Calling it directly here would suspend runBlocking forever:
        // this StandardTestDispatcher advances only when the test pumps it.
        val manualSync = async(start = CoroutineStart.UNDISPATCHED) { repository.triggerSync() }
        try {
            completeScheduledJob(scheduler, manualSync)
            assertTrue(manualSync.await().success)
        } finally {
            manualSync.cancel()
            scheduler.runCurrent()
        }
        assertEquals(manualStart + 1, statusReads.get())
    }

    @Test fun stoppedForegroundCheckCanPollAgainButLockedWorkspaceCannot() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        usePollingScheduler(scheduler, BatteryOptimizationMode.REAL_TIME)
        completeScheduledJob(scheduler, repository.onUnlocked())
        settlePolling(scheduler)
        val first = statusReads.get()
        beforeStatusResponse = { repository.stopStreaming() }
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        beforeStatusResponse = null
        assertEquals(first + 1, statusReads.get())
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals(first + 2, statusReads.get())
        repository.onLocked()
        scheduler.advanceTimeBy(900_000); scheduler.runCurrent()
        assertEquals(first + 2, statusReads.get())
        completeScheduledJob(scheduler, repository.onUnlocked())
        settlePolling(scheduler)
        val reopened = statusReads.get()
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals(reopened + 1, statusReads.get())
    }

    @Test fun modelFailureLeavesOutboxPendingWhileWorkflowMetadataStillRefreshes() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC unavailable local model")
        allowChat = false
        try { repository.sendMessage(session,"SYNTHETIC inquiry"); fail("No model response may be invented.") } catch (_: GatewayException) {}
        val sync = repository.triggerSync()
        assertFalse(sync.success)
        assertTrue(actionReads.get() > 0)
        assertEquals(0L, repository.lastSyncTimestamp.value) // Failed delivery must not advance the successful-sync time.
        assertTrue(repository.lastConnectionCheck.value > 0)
        assertEquals(1,database.chatDao().getQueuedOfflineMessages().size)
        assertTrue(repository.getMessagesForSession(session).first().none { it.sender == "assistant" })
    }

    @Test fun workflowOnlyDeviceDoesNotAttemptUnauthorizedConversationHistory() = runBlocking {
        grantChat = false
        repository.onUnlocked().join()
        assertTrue(repository.triggerSync().success)
        assertEquals(0,conversationReads.get())
        assertTrue(actionReads.get() > 0)
    }

    @Test fun chatEvidenceReturnsDurableCaptureIdentityBeforeAnyUploadAndRetainsItForRetry() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC evidence from chat")
        val attachment = ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9)
        var saved: ChatSubmissionReceipt? = null
        val receipt = repository.sendMessage(session, "SYNTHETIC exact evidence note", attachment) { saved = it }
        assertEquals(receipt, saved)
        assertTrue(receipt.evidenceNote)
        assertEquals(session, receipt.sessionId)
        assertEquals("CAPTURE_PENDING", receipt.status)
        val stored = repository.getDocumentById(receipt.id).first()!!
        val payload = JSONObject(cipher.decrypt(stored.encryptedContent))
        assertEquals(receipt.id, payload.getString("sourceId"))
        assertEquals("SYNTHETIC exact evidence note", payload.getString("text"))
        assertEquals(attachment.base64Data, payload.getJSONObject("localEvidence").getString("data"))
        assertTrue(repository.getMessagesForSession(session).first().isEmpty())
        assertEquals(0, chatRequests.get())
        assertEquals(0, evidenceRequests.get())
        allowStatus = true
        assertTrue(repository.triggerSync().success)
        assertEquals(receipt.id, captureRequests.single().getString("sourceId"))
        assertEquals("CAPTURE_RECEIPT", repository.documents.first().single { it.id == receipt.id }.fileType)
    }

    @Test fun chatEvidenceLocalReceiptPrecedesNetworkAndReportsAcknowledgedCaptureDespiteMetadataFailure() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC durable save receipt")
        allowActionReads = false
        val locallySaved = AtomicBoolean(false)
        beforeEvidenceResponse = { assertTrue("The composer must know the local commit before any upload response.", locallySaved.get()) }
        var saved: ChatSubmissionReceipt? = null
        val receipt = repository.sendMessage(session, "SYNTHETIC evidence note", ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9)) {
            saved = it; locallySaved.set(true)
        }
        assertEquals(receipt.id, saved!!.id)
        assertEquals("CAPTURE_PENDING", saved!!.status)
        assertEquals("CAPTURE_RECEIPT", receipt.status)
        assertTrue(repository.lastError.value!!.contains("Note saved on this phone"))
        assertEquals(1, captureRequests.size)
        assertEquals(0, chatRequests.get())
    }

    @Test fun failedEvidenceValidationProducesNoDurableReceiptAndNoQueueEntry() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC rejected draft")
        var saved: ChatSubmissionReceipt? = null
        try {
            repository.sendMessage(session, "x".repeat(12001), ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9)) { saved = it }
            fail("An oversized note must fail before saving, without truncation.")
        } catch (_: IllegalArgumentException) {}
        assertNull(saved)
        assertTrue(repository.documents.first().isEmpty())
        assertEquals(0, captureRequests.size)
    }

    @Test fun askAgainCreatesNewIdentityWithFullOriginalQuestionAndMinimumOriginalPrivacy() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC ask again")
        val question = "Q".repeat(12000)
        val first = repository.sendMessage(session, question)
        // A long assistant result must never become the next prompt.
        database.chatDao().insertMessage(VaultRecords.encode(EncryptedChatMessage("reply-${first.id}", session, "assistant",
            cipher.encrypt("A".repeat(12050)), status = "SYNCED_DESKTOP"), cipher))
        repository.setSecureClinicalMode(false).join()
        val repeated = repository.askAgain(session, first.id)
        assertNotEquals(first.id, repeated.id)
        assertEquals("SYNCED_DESKTOP", repeated.status)
        assertEquals(2, requests.size)
        assertEquals(question, requests[1].getString("prompt"))
        assertTrue(requests[1].getBoolean("requireLocal"))
        assertTrue(requests[1].getBoolean("requireHipaa"))
        assertNotEquals(requests[0].getString("requestId"), requests[1].getString("requestId"))
        assertEquals(PairingConfigParser.destinationIdentity(config), database.chatDao().messageById(repeated.id)!!.desktopFingerprint)
    }

    @Test fun askAgainRejectsUncertainDeliveryUnknownPairingAndOversizedOriginalWithoutNewRequests() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC no unsafe repeat")
        val original = EncryptedChatMessage("original", session, "user", cipher.encrypt("SYNTHETIC question"),
            status = "REVIEW_REQUIRED", desktopFingerprint = PairingConfigParser.destinationIdentity(config))
        val invalid = listOf(original, original.copy(status = "SYNCED_DESKTOP", desktopFingerprint = null),
            original.copy(status = "SYNCED_DESKTOP", desktopFingerprint = "different-pairing"),
            original.copy(status = "SYNCED_DESKTOP", encryptedContent = cipher.encrypt("x".repeat(12001))))
        for (row in invalid) {
            database.chatDao().insertMessage(VaultRecords.encode(row, cipher))
            try { repository.askAgain(session, row.id); fail("Unsafe original must require review, not create a new request.") }
            catch (_: GatewayException) {}
        }
        assertEquals(0, chatRequests.get())
        assertEquals(1, repository.getMessagesForSession(session).first().size)
    }

    @Test fun failedCaptureRetriesSameSourceAndStillRefreshesOtherDesktopRecords() = runBlocking {
        repository.onUnlocked().join()
        allowCapture = false
        val id = repository.createCapture("SYNTHETIC deferred reminder","reminder")
        assertEquals("CAPTURE_PENDING",repository.documents.first().single { it.id == id }.fileType)
        assertFalse(repository.triggerSync().success)
        assertTrue(actionReads.get() > 0)
        allowCapture = true
        assertTrue(repository.triggerSync().success)
        assertTrue(captureRequests.all { it.getString("sourceId") == id })
        assertEquals("CAPTURE_RECEIPT",repository.documents.first().single { it.id == id }.fileType)
    }

    @Test fun mismatchedAcknowledgmentNeverCreatesAssistantReplyOrDeliveryReceipt() = runBlocking {
        repository.onUnlocked().join()
        wrongChatAcknowledgment = true
        val session = repository.createNewSession("SYNTHETIC bad acknowledgment")
        try { repository.sendMessage(session,"SYNTHETIC request"); fail("Wrong request acknowledgment must fail.") } catch (_: GatewayException) {}
        val rows = repository.getMessagesForSession(session).first()
        assertEquals(1,rows.size)
        assertEquals("REVIEW_REQUIRED",rows.single().status)
        assertNotNull(rows.single().attemptedAt)
    }

    @Test fun metadataRefreshFailureAfterDurableCaptureNeverReportsLocalSaveFailure() = runBlocking {
        repository.onUnlocked().join()
        allowActionReads = false
        val id = repository.createCapture("SYNTHETIC saved note despite unavailable metadata")
        val stored = repository.documents.first().single { it.id == id }
        assertEquals("CAPTURE_RECEIPT",stored.fileType)
        assertTrue(stored.syncedWithDesktop)
        assertEquals(id,captureRequests.single().getString("sourceId"))
        assertTrue(repository.lastError.value!!.contains("Note saved on this phone"))
        assertEquals(1,captureRequests.size)
    }

    @Test fun oversizedChatIsRejectedBeforeQueuingOrNetworkRatherThanSilentlyTruncated() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC chat limits")
        listOf("x".repeat(12001), "三".repeat(12000), "\u0001".repeat(10000)).forEach { text ->
            try { repository.sendMessage(session,text); fail("Oversized text must not be queued or truncated.") } catch (_: GatewayException) {}
        }
        assertTrue(repository.getMessagesForSession(session).first().isEmpty())
        assertEquals(0,chatRequests.get())
    }

    @Test fun actionRevisionIsPreservedAsExactStringAcrossRealHttpsUpdate() = runBlocking {
        val revision = "ab".repeat(32)
        val item = SharedWorkflowService.action(JSONObject().put("id","synthetic-action").put("revision",revision)
            .put("dueAt",JSONObject.NULL).put("reviewRequired",true))
        assertEquals(revision,item.revision)
        val reply = SharedWorkflowService(DesktopNodeService()).updateAction(config,item,"SYNTHETIC owner",null,"Reviewed",false)
        assertEquals(revision,reply.revision)
        assertEquals("SYNTHETIC owner",reply.owner)
    }

    @Test fun productionUnsupportedAgentCannotCreateFakeDesktopWork() = runBlocking {
        repository.onUnlocked().join()
        try { repository.startAgent("SYNTHETIC agent"); fail("Unsupported execution must fail.") } catch (_: GatewayException) {}
        assertTrue(repository.tasks.first().isEmpty())
        assertEquals(0,chatRequests.get())
    }

    @Test fun certificateAndPairingDestinationMustMatchBeforeAnyRequest() = runBlocking {
        val service = DesktopNodeService()
        try { service.getNodeStats(config.copy(certificateFingerprint="0".repeat(64))); fail("Bad pin must fail.") } catch (_: GatewayException) {}
        try { service.getNodeStats(config.copy(hostIp="192.168.2.10")); fail("Wrong certificate address must fail.") } catch (_: GatewayException) {}
        assertEquals(0,chatRequests.get())
        assertFalse(PairingConfigParser.privateAddress("8.8.8.8"))
        assertFalse(PairingConfigParser.privateAddress("127.00.0.1"))
        assertTrue(PairingConfigParser.privateAddress("100.64.1.2"))
        val query = listOf("host" to config.hostIp,"port" to config.port.toString(),"scheme" to "https","key" to config.pairKey,
            "fingerprint" to config.certificateFingerprint,"certificate" to config.certificateDer)
            .joinToString("&") { (key,value) -> "$key=${URLEncoder.encode(value,"UTF-8")}" }
        val parsed = PairingConfigParser.parse("privateai://pair?$query")
        assertEquals(config.certificateFingerprint,parsed.certificateFingerprint)
        assertEquals(config.pairKey,parsed.pairKey)
        try { PairingConfigParser.parse("privateai://pair?$query&key=${config.pairKey}"); fail("Duplicates must fail.") } catch (_: IllegalArgumentException) {}
    }

    @Test fun operationalEvidenceIsReceiptBackedAndNeverPretendsToBeVision() = runBlocking {
        repository.onUnlocked().join()
        val id = repository.createCapture("SYNTHETIC room light needs review","round", location="SYNTHETIC room")
        val stored = repository.documents.first().single { it.id == id }
        assertEquals("CAPTURE_RECEIPT",stored.fileType)
        assertTrue(stored.syncedWithDesktop)
        assertTrue(database.chatDao().getAllSessions().first().isEmpty())
        assertEquals(0,chatRequests.get())
        // A completed upload can acknowledge the first chunk of a replay. Do not send remaining chunks.
        val bytes = ByteArray(160 * 1024).apply { byteArrayOf(-119,80,78,71,13,10,26,10).copyInto(this) }
        val file = ChatAttachment("SYNTHETIC.png","IMAGE",Base64.encodeToString(bytes,Base64.NO_WRAP),bytes.size.toLong())
        assertEquals("synthetic-evidence",SharedWorkflowService(DesktopNodeService()).uploadEvidence(config,id,file))
        assertEquals(1,evidenceRequests.get())
    }

    @Test fun phoneLockRejectsInFlightChunkAndNeverSendsTheNextChunk() = runBlocking {
        val unlocked = AtomicBoolean(true)
        val epoch = AtomicLong(1)
        val transport = DesktopNodeService { if (unlocked.get()) GatewayAuthorizationEpoch(epoch.get()) else null }
        val workflow = SharedWorkflowService(transport)
        val bytes = ByteArray(100 * 1024) { 'x'.code.toByte() }
        val file = ChatAttachment("SYNTHETIC.txt","DOCUMENT",Base64.encodeToString(bytes,Base64.NO_WRAP),bytes.size.toLong())
        val sourceId = java.util.UUID.randomUUID().toString()
        evidenceComplete = false
        beforeEvidenceResponse = { unlocked.set(false); epoch.incrementAndGet() }
        try { workflow.uploadEvidence(config,sourceId,file); fail("A response after locking must not authorize another chunk.") }
        catch (e: GatewayException) { assertEquals("PHONE_LOCKED",e.code) }
        assertEquals(1,evidenceRequests.get())
        assertEquals(2,evidenceInputs.single().getInt("total"))
        assertEquals(0,evidenceInputs.single().getInt("index"))
        beforeEvidenceResponse = null; evidenceComplete = true; unlocked.set(true); epoch.incrementAndGet()
        assertEquals("synthetic-evidence",workflow.uploadEvidence(config,sourceId,file))
        assertEquals(2,evidenceRequests.get())
        assertEquals(evidenceInputs[0].getString("uploadId"),evidenceInputs[1].getString("uploadId"))
        assertEquals(evidenceInputs[0].getString("data"),evidenceInputs[1].getString("data"))
    }

    @Test fun lockThenReunlockCannotAcceptAResponseFromThePreviousAuthorizationEpoch() = runBlocking {
        val epoch = AtomicLong(1)
        val transport = DesktopNodeService { GatewayAuthorizationEpoch(epoch.get()) }
        evidenceComplete = false
        beforeEvidenceResponse = { epoch.addAndGet(2) } // A new OS unlock is already active when the old response arrives.
        val bytes = ByteArray(100 * 1024) { 'x'.code.toByte() }
        try {
            SharedWorkflowService(transport).uploadEvidence(config,java.util.UUID.randomUUID().toString(),
                ChatAttachment("SYNTHETIC.txt","DOCUMENT",Base64.encodeToString(bytes,Base64.NO_WRAP),bytes.size.toLong()))
            fail("An unlocked boolean cannot authorize a response belonging to an earlier unlock.")
        } catch (e: GatewayException) { assertEquals("PHONE_LOCKED",e.code) }
        assertEquals(1,evidenceRequests.get())
    }

    @Test fun reportMetadataDoesNotInventProgressOrResidentFindings() {
        val jobs = DesktopNodeService.parseTasks(JSONObject("""{"tasks":[{"id":"synthetic-job","status":"queued","created":null,"updated":null}]}"""))
        assertEquals("REPORT",jobs.single().category)
        assertEquals(0L,jobs.single().startedAt)
        assertEquals("Not provided",jobs.single().modelName)
        assertTrue(jobs.single().resultSummary!!.contains("Status only"))
        val alerts = DesktopNodeService.parseAlerts(JSONObject("""{"alerts":[{"id":"synthetic-alert","severity":"high","status":"open","category":null}]}"""))
        assertEquals(0L,alerts.single().timestamp)
        assertTrue(alerts.single().message.contains("empty list is not an all-clear"))
    }

    @Test fun lastSuccessfulSyncSurvivesOfflineReopenAndDoesNotFollowNewPairing() = runBlocking {
        repository.onUnlocked().join()
        assertTrue(repository.triggerSync().success)
        val saved = repository.lastSyncTimestamp.value
        assertTrue(saved > 0)
        allowStatus = false
        assertFalse(repository.checkConnection())
        assertEquals(saved, repository.lastSyncTimestamp.value)
        repository.onLocked();repository.onUnlocked().join()
        assertEquals(saved, repository.lastSyncTimestamp.value)
        assertEquals(ConnectionStatus.DISCONNECTED, repository.connectionStatus.value)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureConfigStore(context,cipher)
        assertEquals(0L, store.lastSync(config.copy(pairKey="B".repeat(43))))
        assertEquals(0L, store.lastSync(config.copy(simulatedNodeEnabled=true)))
    }

    private fun conversation(id: String, revision: Long) = JSONObject().put("sessionId", id).put("title", "SYNTHETIC $id").put("updated", revision)

    @Test fun unchangedHistoriesAreSkippedAndFailedImportsDoNotAdvanceRevision() = runBlocking {
        repository.onUnlocked().join()
        conversationRows = org.json.JSONArray().put(conversation("shared_synthetic", 1))
        assertTrue(repository.triggerSync().success)
        assertEquals(1, historyReads.get())
        assertEquals(1L, database.chatDao().sessionById("shared_synthetic")!!.desktopRevision)
        assertTrue(repository.triggerSync().success)
        assertEquals(1, historyReads.get())
        conversationRows = org.json.JSONArray().put(conversation("shared_synthetic", 2))
        historyFailureCode = "BUSY"
        assertFalse(repository.triggerSync().success)
        assertEquals(1L, database.chatDao().sessionById("shared_synthetic")!!.desktopRevision)
        historyFailureCode = null
        assertTrue(repository.triggerSync().success)
        assertEquals(2L, database.chatDao().sessionById("shared_synthetic")!!.desktopRevision)
        assertEquals(3, historyReads.get())
    }

    @Test fun largeConversationInventoryUsesBoundedRefreshPasses() = runBlocking {
        repository.onUnlocked().join()
        conversationRows = org.json.JSONArray().apply { repeat(100) { put(conversation("shared_$it", 1)) } }
        assertFalse(repository.triggerSync().success)
        assertEquals(20, historyReads.get())
        assertTrue(repository.lastError.value!!.contains("More changed"))
        assertFalse(repository.triggerSync().success)
        assertEquals(40, historyReads.get())
        assertEquals(40, database.chatDao().allSessionsForMigration().size)
    }

    @Test fun transientConflictsKeepOriginalIdentityPolicyAndFirstAttempt() = runBlocking {
        repository.onUnlocked().join()
        for (code in listOf("BUSY", "COMPANION_PAUSED")) {
            val session = repository.createNewSession("SYNTHETIC $code")
            chatFailureCode = code
            try { repository.sendMessage(session, "SYNTHETIC retained input"); fail("Temporary refusal must remain pending.") }
            catch (e: GatewayException) { assertEquals(409, e.httpStatus) }
            val pending = database.chatDao().getQueuedOfflineMessages().single()
            val input = synchronized(requests) { requests.last().toString() }
            assertNotNull(pending.attemptedAt)
            repository.setSecureClinicalMode(false).join()
            chatFailureCode = null
            assertTrue(repository.triggerSync().success)
            val completed = database.chatDao().messageById(pending.id)!!
            assertEquals("SYNCED_DESKTOP", completed.status)
            assertEquals(pending.requestId, completed.requestId)
            assertEquals(pending.attemptedAt, completed.attemptedAt)
            assertEquals(input, synchronized(requests) { requests.last().toString() })
            assertTrue(completed.requireLocal); assertTrue(completed.requireHipaa)
            repository.setSecureClinicalMode(true).join()
        }
    }

    @Test fun interruptedAndChangedConflictsStayReviewRequired() = runBlocking {
        repository.onUnlocked().join()
        for (code in listOf("INTERRUPTED", "CHANGED")) {
            chatFailureCode = code
            val session = repository.createNewSession("SYNTHETIC $code")
            try { repository.sendMessage(session, "SYNTHETIC do not replay"); fail("Uncertain delivery must stop.") }
            catch (_: GatewayException) { }
            assertEquals("REVIEW_REQUIRED", repository.getMessagesForSession(session).first().single().status)
        }
        val before = chatRequests.get()
        chatFailureCode = null
        repository.triggerSync()
        assertEquals(before, chatRequests.get())
    }

    @Test fun rateLimitBackoffPreventsNetworkUntilRetryAfterAndPreservesRequest() = runBlocking {
        val elapsed = AtomicLong(1)
        val transport = DesktopNodeService(elapsedMillis = elapsed::get)
        repository.close()
        repository = PrivateAIRepository(database, nodeService = transport, configStore = configuration, cipher = cipher)
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC rate limit")
        chatFailureCode = "RATE_LIMITED"
        try { repository.sendMessage(session, "SYNTHETIC same request"); fail("429 must not acknowledge.") }
        catch (e: GatewayException) { assertEquals(429, e.httpStatus) }
        val pending = database.chatDao().getQueuedOfflineMessages().single()
        val before = statusReads.get()
        chatFailureCode = null
        assertFalse(repository.triggerSync().success)
        assertEquals(before, statusReads.get())
        assertEquals(60_000L, transport.retryDelayMillis())
        elapsed.addAndGet(60_000)
        assertTrue(repository.triggerSync().success)
        val completed = database.chatDao().messageById(pending.id)!!
        assertEquals(pending.requestId, completed.requestId)
        assertEquals(pending.attemptedAt, completed.attemptedAt)
        assertEquals(2, chatRequests.get())
    }

    @Test fun sharedTransportBudgetWaitsBeforeSendingBeyondOneHundredRequests() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val transport = DesktopNodeService(elapsedMillis = { scheduler.currentTime })
        try {
            repeat(100) { transport.getNodeStats(config) }
            val next = async(StandardTestDispatcher(scheduler)) { transport.getNodeStats(config) }
            scheduler.runCurrent()
            assertFalse(next.isCompleted)
            assertEquals(100, statusReads.get())
            scheduler.advanceTimeBy(59_999); scheduler.runCurrent()
            assertEquals(100, statusReads.get())
            scheduler.advanceTimeBy(1); scheduler.runCurrent()
            completeScheduledJob(scheduler, next)
            assertEquals(101, statusReads.get())
        } finally { transport.close() }
    }

    @Test fun automaticUnlockRunsFullSyncAndPendingRetryPrecedesLeaseExpiry() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC automatic retry")
        allowChat = false
        try { repository.sendMessage(session, "SYNTHETIC pending"); fail("No acknowledgment expected.") } catch (_: GatewayException) { }
        val pending = database.chatDao().getQueuedOfflineMessages().single()
        repository.close()
        configuration.save(config.copy(batteryMode = BatteryOptimizationMode.SMART_ECO))
        val scheduler = TestCoroutineScheduler()
        val remaining = { (300_000L - scheduler.currentTime).coerceAtLeast(0) }
        val transport = DesktopNodeService { if (remaining() > 0) GatewayAuthorizationEpoch(1) else null }
        repository = PrivateAIRepository(database, nodeService = transport, configStore = configuration, cipher = cipher,
            dispatcher = StandardTestDispatcher(scheduler), remainingLeaseMillis = remaining)
        completeScheduledJob(scheduler, repository.onUnlocked())
        assertTrue(conversationReads.get() > 0) // Full sync on unlock, before the first periodic interval.
        assertEquals(2, chatRequests.get())
        allowChat = true
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals("SYNCED_DESKTOP", database.chatDao().messageById(pending.id)!!.status)
        assertEquals(pending.requestId, requests.last().getString("requestId"))
        val before = statusReads.get()
        scheduler.advanceTimeBy(300_000); scheduler.runCurrent()
        assertEquals(before, statusReads.get())
    }

    @Test fun manualUnlockDoesNotImportOrReplayUntilExplicitSync() = runBlocking {
        repository.onUnlocked().join()
        assertEquals(1, statusReads.get())
        assertEquals(0, conversationReads.get())
        assertEquals(0, actionReads.get())
        assertTrue(repository.triggerSync().success)
        assertEquals(1, conversationReads.get())
    }

    @Test fun stoppingInitialFullSyncLeavesFutureAutomaticChecksEnabled() = runBlocking {
        repository.close()
        configuration.save(config.copy(batteryMode = BatteryOptimizationMode.REAL_TIME))
        val scheduler = TestCoroutineScheduler()
        repository = PrivateAIRepository(database, configStore = configuration, cipher = cipher, dispatcher = StandardTestDispatcher(scheduler))
        beforeStatusResponse = { repository.stopStreaming() }
        completeScheduledJob(scheduler, repository.onUnlocked())
        assertTrue(repository.vaultReady.value)
        beforeStatusResponse = null
        val before = statusReads.get()
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()
        completeScheduledCheck(scheduler)
        assertEquals(before + 1, statusReads.get())
        assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
    }

    @Test fun alertCoverageIsCarriedThroughAndRevokedWithWorkspace() = runBlocking {
        grantAlerts = true
        alertResponse.put("monitoringEnabled", false).put("isSurveillanceComplete", false).put("total", 200).put("truncated", true)
        repository.onUnlocked().join()
        assertTrue(repository.triggerSync().success)
        assertEquals(AlertCoverage(true, false, false, 200, true), repository.alertCoverage.value)
        repository.onLocked()
        assertEquals(AlertCoverage(), repository.alertCoverage.value)
    }

    @Test fun meetingReplayContinuesBeyondThirtyAndSubmissionWaitsForFinishReceipt() = runBlocking {
        meetingCapability = true
        repository.onUnlocked().join()
        val id = repository.createMeeting("SYNTHETIC long meeting")
        val wav = com.example.audio.PcmWave.encode(ShortArray(1600), 1600)
        repeat(32) { repository.saveMeetingAudio(id, wav) }
        repository.finishMeeting(id)
        assertTrue(repository.phoneMeetings.value.single().finishQueued)
        assertFalse(repository.phoneMeetings.value.single().finishAcknowledged)
        assertTrue(repository.triggerSync().success)
        assertEquals(34, meetingRequests.size)
        assertTrue(meetingRequests.last().first.endsWith("/finish"))
        assertEquals(32, meetingRequests.last().second.getInt("expectedChunkCount"))
        assertTrue(repository.phoneMeetings.value.single().finishAcknowledged)
        assertEquals(0, repository.meetingPending.value)
    }

    @Test fun permanentlyFailedMeetingRetainsAudioAndDoesNotBlockIndependentMeeting() = runBlocking {
        meetingCapability = true
        repository.onUnlocked().join()
        val wav = com.example.audio.PcmWave.encode(ShortArray(1600), 1600)
        for (code in listOf("NOT_FOUND", "CHANGED")) {
            val failed = repository.createMeeting("SYNTHETIC $code")
            repository.saveMeetingAudio(failed, wav); repository.finishMeeting(failed)
            val independent = repository.createMeeting("SYNTHETIC independent")
            repository.saveMeetingAudio(independent, wav); repository.finishMeeting(independent)
            permanentlyFailedMeeting = failed; permanentMeetingCode = code
            assertFalse(repository.triggerSync().success)
            val binding = PairingConfigParser.destinationIdentity(config)
            val reopened = com.example.data.repository.MeetingOutbox(database, cipher).list(binding)
            assertNotNull(reopened.first { it.sourceId == failed }.reviewError)
            assertFalse(reopened.first { it.sourceId == failed }.finishAcknowledged)
            assertTrue(reopened.first { it.sourceId == independent }.finishAcknowledged)
            val retained = database.pendingOperationDao().allAfterForPolicy(null, 100).first {
                it.operation == "MEETING_AUDIO" && JSONObject(cipher.decrypt(it.encryptedPayload)).optString("sourceId") == failed
            }
            assertEquals("REVIEW_REQUIRED", retained.status)
            assertArrayEquals(wav, Base64.decode(JSONObject(cipher.decrypt(retained.encryptedPayload)).getString("audio"), Base64.NO_WRAP))
            assertFalse(meetingRequests.any { it.first.endsWith("/finish") && it.second.getString("sourceId") == failed })
        }
    }

    @Test fun missingWorkflowOrMeetingCapabilityLeavesFinishVisiblyPending() = runBlocking {
        repository.onUnlocked().join()
        val id = repository.createMeeting("SYNTHETIC denied access")
        repository.finishMeeting(id)
        assertFalse(repository.triggerSync().success) // No meetings capability.
        meetingCapability = true; grantWorkflow = false
        assertFalse(repository.triggerSync().success)
        assertEquals(2, repository.meetingPending.value)
        assertTrue(meetingRequests.isEmpty())
        assertTrue(repository.lastError.value!!.contains("Workflow"))
        assertFalse(repository.phoneMeetings.value.single().finishAcknowledged)
    }

    @Test fun unsupportedEvidenceDoesNotSaveDeliveryOrClearEditableDraft() = runBlocking {
        repository.onUnlocked().join()
        for (name in listOf("synthetic.docx", "synthetic.csv", "synthetic.webp")) {
            val attachment = ChatAttachment(name, if (name.endsWith("webp")) "IMAGE" else "DOCUMENT", "c3ludGhldGlj", 9)
            val initial = repository.loadLocalDraft("capture")
            val form = JSONObject().put("note", "SYNTHETIC keep this draft")
                .put("evidence", com.example.ui.components.attachmentForm(attachment)).toString()
            val token = repository.updateLocalDraft(initial.token, form, true)
            repository.flushLocalDrafts()
            val before = repository.currentLocalDraft(token).form
            val submission = repository.prepareLocalDraftSubmission(token, before, setOf("note", "evidence"))
            var saved = false
            try {
                repository.createCapture("SYNTHETIC keep this draft", attachment = attachment, draftSubmission = submission, onLocalSave = { saved = true })
                fail("Unsupported evidence must fail before saving a delivery operation.")
            } catch (e: EvidenceValidationException) { assertTrue(e.message!!.contains("JPEG, PNG, PDF, TXT")) }
            assertFalse(saved)
            assertEquals(before, repository.currentLocalDraft(token).form)
            assertTrue(repository.documents.first().isEmpty())
            assertTrue(captureRequests.isEmpty())
        }
    }

    @Test fun queuedStorageSaveRejectsReplacementPairingBeforeUsingItsConfiguration() = runBlocking {
        repository.close()
        val dispatcher = HoldingDispatcher()
        repository = PrivateAIRepository(database, configStore = configuration, demoDatabase = demoDatabase, cipher = cipher, dispatcher = dispatcher)
        repository.onUnlocked().join()
        try {
            dispatcher.holdNext()
            val save = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.saveDocument("SYNTHETIC stale save", "TEXT", "SYNTHETIC original pairing", "") }
            }
            val replacement = config.copy(pairKey = "B".repeat(43))
            repository.pairSecure(pairingLink(replacement))
            assertTrue(repository.vaultReady.value)
            assertEquals(replacement, repository.nodeConfig.value)
            dispatcher.release()
            assertTrue(save.await().exceptionOrNull() is IllegalStateException)
            assertTrue(database.documentDao().allMetadata().isEmpty())
            assertTrue(demoDatabase.documentDao().allMetadata().isEmpty())
            assertTrue(captureRequests.isEmpty())
        } finally { dispatcher.releaseAll() }
    }

    @Test fun queuedStorageDeleteCannotCrossRealAndDemoWorkspaces() = runBlocking {
        val row = VaultRecords.encode(EncryptedDocument("synthetic-same-id", "Synthetic retained", "TEXT",
            cipher.encrypt("SYNTHETIC retained body"), "same", 23), cipher)
        database.documentDao().insertDocument(row)
        demoDatabase.documentDao().insertDocument(row)
        repository.close()
        val dispatcher = HoldingDispatcher()
        repository = PrivateAIRepository(database, configStore = configuration, demoDatabase = demoDatabase, cipher = cipher, dispatcher = dispatcher)
        repository.onUnlocked().join()
        try {
            for (demo in listOf(true, false)) {
                dispatcher.holdNext()
                val deletion = async(start = CoroutineStart.UNDISPATCHED) { runCatching { repository.deleteDocument(row.id) } }
                repository.updateConfig(repository.nodeConfig.value.copy(simulatedNodeEnabled = demo)).join()
                assertTrue(repository.vaultReady.value)
                assertEquals(demo, repository.nodeConfig.value.simulatedNodeEnabled)
                dispatcher.release()
                assertTrue(deletion.await().exceptionOrNull() is IllegalStateException)
                assertEquals(row, database.documentDao().hydratedById(row.id))
                assertEquals(row, demoDatabase.documentDao().hydratedById(row.id))
            }
        } finally { dispatcher.releaseAll() }
    }

    @Test fun admittedStorageCommitFinishesOnlyInItsCapturedDatabaseAfterWorkspaceSwitch() = runBlocking {
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val actual = cipher
        val blocking = object : VaultCipher {
            override fun encrypt(plaintext: String): String {
                if (plaintext == "SYNTHETIC bound body") {
                    reached.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                return actual.encrypt(plaintext)
            }
            override fun decrypt(encryptedPayload: String) = actual.decrypt(encryptedPayload)
        }
        repository.close()
        repository = PrivateAIRepository(database, configStore = configuration, demoDatabase = demoDatabase, cipher = blocking)
        repository.onUnlocked().join()
        val save = async(Dispatchers.IO) { repository.saveDocument("SYNTHETIC admitted", "TEXT", "SYNTHETIC bound body", "") }
        try {
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            repository.updateConfig(config.copy(simulatedNodeEnabled = true)).join()
            assertTrue(repository.vaultReady.value)
            release.countDown(); save.await()
            val stored = database.documentDao().allMetadata().single()
            assertEquals("SYNTHETIC bound body", actual.decrypt(database.documentDao().hydratedById(stored.id)!!.encryptedContent))
            assertTrue(demoDatabase.documentDao().allMetadata().isEmpty())
        } finally { release.countDown(); save.cancel() }
    }

    private fun pairingLink(value: DesktopNodeConfig): String = "privateai://pair?" + listOf(
        "host" to value.hostIp, "port" to value.port.toString(), "scheme" to "https", "key" to value.pairKey,
        "fingerprint" to value.certificateFingerprint, "certificate" to value.certificateDer
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    @Test fun replacementPairingCannotInheritThePreviousDesktopsAlertCoverage() = runBlocking {
        grantAlerts = true
        alertResponse.put("monitoringEnabled", true).put("isSurveillanceComplete", true).put("total", 0).put("truncated", false)
        repository.onUnlocked().join()
        for ((key, grantsAlerts) in listOf("B" to false, "C" to true)) {
            grantAlerts = true
            assertTrue(repository.triggerSync().success)
            assertEquals(AlertCoverage(true, true, true, 0, false), repository.alertCoverage.value)
            grantAlerts = grantsAlerts
            repository.pairSecure(pairingLink(config.copy(pairKey = key.repeat(43))))
            assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
            assertEquals(grantsAlerts, "alerts" in repository.nodeStats.value.scopes)
            assertEquals(AlertCoverage(scopeGranted = if (grantsAlerts) null else false), repository.alertCoverage.value)
        }
    }

    @Test fun overlappingSyncAndImmediateCaptureReplayCannotDowngradeAcknowledgedReceipts() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        val first = repository.createCapture("SYNTHETIC first pending")
        val second = repository.createCapture("SYNTHETIC second pending")
        for ((index, id) in listOf(first, second).withIndex()) {
            val row = database.documentDao().hydratedById(id)!!
            database.documentDao().insertDocument(row.copy(updatedAt = index + 1L))
        }
        allowStatus = true
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val saved = CountDownLatch(1)
        val firstResponse = AtomicBoolean(true)
        beforeCaptureResponse = { input ->
            if (input.getString("sourceId") == first && firstResponse.compareAndSet(true, false)) {
                reached.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val sync = async(Dispatchers.IO) { repository.triggerSync() }
        var immediate: kotlinx.coroutines.Deferred<String>? = null
        try {
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            val saving = async(Dispatchers.IO) { repository.createCapture("SYNTHETIC concurrent save", onLocalSave = { saved.countDown() }) }
            immediate = saving
            assertTrue(saved.await(10, TimeUnit.SECONDS))
            // The local save completes while the earlier replay owns delivery. The second
            // pass cannot finish (or submit the same IDs) until that owner is released.
            assertNull(withTimeoutOrNull(2_000) { saving.await() })
            assertEquals(listOf(first), synchronized(captureRequests) { captureRequests.map { it.getString("sourceId") } })
            release.countDown()
            assertTrue(sync.await().success)
            val third = saving.await()
            for (id in listOf(first, second, third)) {
                val row = repository.getDocumentById(id).first()!!
                assertEquals("CAPTURE_RECEIPT", row.fileType)
                assertTrue(row.syncedWithDesktop)
                assertEquals(id, JSONObject(cipher.decrypt(row.encryptedContent)).getJSONObject("capture").getString("sourceId"))
                assertEquals(1, synchronized(captureRequests) { captureRequests.count { it.getString("sourceId") == id } })
            }
        } finally { beforeCaptureResponse = null; release.countDown(); sync.cancel(); immediate?.cancel() }
    }
}
