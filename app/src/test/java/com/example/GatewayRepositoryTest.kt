package com.example

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.repository.PrivateAIRepository
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Uses a clearly synthetic loopback TLS fixture, never a live account or workstation key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GatewayRepositoryTest {
    private lateinit var server: HttpsServer
    private lateinit var config: DesktopNodeConfig
    private lateinit var database: AppDatabase
    private lateinit var demoDatabase: AppDatabase
    private lateinit var repository: PrivateAIRepository
    private lateinit var cipher: VaultCipher
    private val chatRequests = AtomicInteger()
    private val evidenceRequests = AtomicInteger()
    private val actionReads = AtomicInteger()
    private val conversationReads = AtomicInteger()
    private val captureRequests = mutableListOf<JSONObject>()
    private val requests = mutableListOf<JSONObject>()
    private val evidenceInputs = mutableListOf<JSONObject>()
    @Volatile private var allowChat = true
    @Volatile private var allowStatus = true
    @Volatile private var allowCapture = true
    @Volatile private var allowActionReads = true
    @Volatile private var grantChat = true
    @Volatile private var wrongChatAcknowledgment = false
    @Volatile private var evidenceComplete = true
    @Volatile private var beforeEvidenceResponse: (() -> Unit)? = null
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
        config = DesktopNodeConfig(hostIp = "127.0.0.1", port = server.address.port, pairKey = "A".repeat(43),
            certificateDer = Base64.encodeToString(cert, Base64.NO_WRAP),
            certificateFingerprint = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it.toInt() and 255) })
        server.createContext("/mobile/api/v1/") { exchange ->
            val input = if (exchange.requestMethod == "POST") JSONObject(exchange.requestBody.bufferedReader().readText()) else JSONObject()
            var code = 200
            val output = when (exchange.requestURI.path) {
                GatewayEndpoints.STATUS -> if (!allowStatus) { code = 503; JSONObject().put("error",JSONObject().put("code","UNAVAILABLE")) } else JSONObject().put("status","ONLINE").put("protocolVersion",1)
                    .put("serverName","SYNTHETIC test server").put("loadedModel",JSONObject.NULL).put("localModelState","unloaded")
                    .put("cpuUsage",JSONObject.NULL).put("gpuUsage",JSONObject.NULL).put("vramUsedGb",JSONObject.NULL).put("vramTotalGb",JSONObject.NULL)
                    .put("activeJobs",0).put("scopes",JSONObject().put("chat",grantChat).put("tasks",true).put("workflow",true))
                    .put("capabilities",org.json.JSONArray().put("conversations"))
                GatewayEndpoints.CHAT_SEND -> {
                    chatRequests.incrementAndGet()
                    synchronized(requests) { requests.add(input) }
                    if (!allowChat) { code = 503; JSONObject().put("error",JSONObject().put("code","LOCAL_UNAVAILABLE")) }
                    else JSONObject().put("reply","SYNTHETIC real HTTP reply").put("sessionId",input.getString("sessionId"))
                        .put("requestId",if (wrongChatAcknowledgment) "wrong-request" else input.getString("requestId")).put("processing",JSONObject().put("provider","local").put("model","SYNTHETIC test engine"))
                }
                GatewayEndpoints.TASKS -> JSONObject().put("tasks",org.json.JSONArray())
                "/mobile/api/v1/actions" -> {
                    actionReads.incrementAndGet()
                    if (!allowActionReads) { code = 503; JSONObject().put("error",JSONObject().put("code","BUSY")) }
                    else JSONObject().put("captures",org.json.JSONArray()).put("actions",org.json.JSONArray()).put("drafts",org.json.JSONArray())
                }
                "/mobile/api/v1/conversations" -> {
                    conversationReads.incrementAndGet()
                    if (!grantChat) { code = 403; JSONObject().put("error",JSONObject().put("code","POLICY")) }
                    else JSONObject().put("conversations",org.json.JSONArray())
                }
                "/mobile/api/v1/actions/update" -> input.put("title","SYNTHETIC action").put("description","SYNTHETIC review").put("status","needs-review").put("unknownFields",org.json.JSONArray())
                "/mobile/api/v1/captures/create" -> {
                    synchronized(captureRequests) { captureRequests.add(input) }
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
        val configuration = SecureConfigStore(context,cipher)
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
        repository.setSecureClinicalMode(false) // A later preference must never weaken the queued request.
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
        repository.setSecureClinicalMode(false)
        allowChat = false
        val session = repository.createNewSession("SYNTHETIC previous policy")
        try { repository.sendMessage(session,"SYNTHETIC request"); fail("Actual server failure must propagate.") } catch (_: GatewayException) {}
        assertFalse(requests.single().getBoolean("requireLocal"))
        repository.setSecureClinicalMode(true)
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
        assertNotNull(repository.lastSyncTimestamp.value.takeIf { it > 0 })
    }

    @Test fun neverAttemptedOfflineRequestCanAdoptStricterPrivacyBeforeItsFirstSend() = runBlocking {
        allowStatus = false
        repository.onUnlocked().join()
        repository.setSecureClinicalMode(false)
        val session = repository.createNewSession("SYNTHETIC offline")
        repository.sendMessage(session,"SYNTHETIC first attempt")
        val before = database.chatDao().getQueuedOfflineMessages().single()
        assertNull(before.attemptedAt)
        assertFalse(before.requireLocal)
        allowStatus = true
        repository.setSecureClinicalMode(true)
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
        val preserved = repository.documents.first().single { it.id == captureId }
        assertEquals("CAPTURE_REVIEW_REQUIRED",preserved.fileType)
        assertTrue(cipher.decrypt(preserved.encryptedContent).contains("SYNTHETIC protected observation"))
    }

    @Test fun modelFailureLeavesOutboxPendingWhileWorkflowMetadataStillRefreshes() = runBlocking {
        repository.onUnlocked().join()
        val session = repository.createNewSession("SYNTHETIC unavailable local model")
        allowChat = false
        try { repository.sendMessage(session,"SYNTHETIC inquiry"); fail("No model response may be invented.") } catch (_: GatewayException) {}
        val sync = repository.triggerSync()
        assertFalse(sync.success)
        assertTrue(actionReads.get() > 0)
        assertTrue(repository.lastSyncTimestamp.value > 0)
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
}
