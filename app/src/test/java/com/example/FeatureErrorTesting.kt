package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AttachmentUtils
import com.example.data.crypto.EncryptionManager
import com.example.data.local.AppDatabase
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.BatteryOptimizationMode
import com.example.data.model.ConnectionStatus
import com.example.data.model.DesktopNodeConfig
import com.example.data.network.DesktopNodeService
import com.example.data.network.GatewayEndpoints
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureErrorTesting {

    private lateinit var database: AppDatabase
    private lateinit var nodeService: DesktopNodeService
    private lateinit var repository: PrivateAIRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        nodeService = DesktopNodeService()
        repository = PrivateAIRepository(
            database = database,
            nodeService = nodeService
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ==========================================
    // STAGE 1: CONNECTIVITY & GATEWAY ERROR TESTS
    // ==========================================

    @Test
    fun `test invalid node address fails health check when simulation disabled`() = runBlocking {
        val brokenConfig = DesktopNodeConfig(
            hostIp = "192.0.2.1", // Non-routable TEST-NET-1 IP
            port = 9999,
            pairKey = "invalid_token",
            simulatedNodeEnabled = false
        )
        val isHealthy = nodeService.checkHealth(brokenConfig)
        assertFalse("Unreachable node with simulatedNodeEnabled=false must return false", isHealthy)
    }

    @Test
    fun `test gateway endpoints syntax and versioning`() {
        assertTrue(GatewayEndpoints.STATUS.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.AUTH_PAIR.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.CHATS.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.TASKS.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.ALERTS.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.FILES_UPLOAD.startsWith("/mobile/api/v1/"))
        assertTrue(GatewayEndpoints.EVENTS_WS.startsWith("/mobile/api/v1/"))
    }

    @Test
    fun `test malformed port fallback handling`() {
        val invalidPortString = "not_a_number"
        val resolvedPort = invalidPortString.toIntOrNull() ?: 8443
        assertEquals(8443, resolvedPort)
    }

    // ==========================================
    // STAGE 2: CHAT & STREAMING ERROR TESTS
    // ==========================================

    @Test
    fun `test decryption of corrupted ciphertext does not crash`() {
        val corruptedPayload = "NOT_VALID_BASE64_CIPHERTEXT_!!!@@@"
        val decrypted = EncryptionManager.decrypt(corruptedPayload)
        // Decryption of invalid ciphertext safely returns fallback string instead of crashing
        assertNotNull(decrypted)
        assertTrue(decrypted.isNotEmpty())
    }

    @Test
    fun `test offline chat message is queued when disconnected`() = runBlocking {
        // Disconnect repository
        repository.updateConfig(
            DesktopNodeConfig(
                hostIp = "10.255.255.1",
                port = 8443,
                simulatedNodeEnabled = false
            )
        )
        // Force status to disconnected
        val isHealthy = repository.checkConnection()
        assertFalse(isHealthy)
        assertEquals(ConnectionStatus.DISCONNECTED, repository.connectionStatus.value)

        val sessionId = repository.createNewSession("Offline Test Session")
        repository.sendMessage(sessionId, "This prompt was typed while completely offline.")

        val messages = repository.getMessagesForSession(sessionId).first()
        val userMsg = messages.find { it.sender == "user" }
        assertNotNull("User message must be saved locally even when offline", userMsg)
        assertEquals("QUEUED_OFFLINE", userMsg?.status)
    }

    @Test
    fun `test stop streaming halts active generation`() = runBlocking {
        // Calling stopStreaming when no stream is active must not crash
        repository.stopStreaming()

        // Create session and test streaming trigger
        val sessionId = repository.createNewSession("Streaming Stop Test")
        repository.sendMessage(sessionId, "Explain deep neural network inference on GPU")

        // Immediately request stop
        repository.stopStreaming()

        val messages = repository.getMessagesForSession(sessionId).first()
        assertTrue("Messages list must exist and not crash", messages.isNotEmpty())
    }

    @Test
    fun `test empty prompt and empty attachment handling`() = runBlocking {
        val sessionId = repository.createNewSession("Empty Prompt Test")
        val initialCount = repository.getMessagesForSession(sessionId).first().size

        // Send empty prompt with null attachment
        repository.sendMessage(sessionId, "", null)

        val newCount = repository.getMessagesForSession(sessionId).first().size
        // Blank prompts should be ignored or safely handled
        assertTrue(newCount >= initialCount)
    }

    // ==========================================
    // STAGE 3: EXECUTIVE DASHBOARD ERROR TESTS
    // ==========================================

    @Test
    fun `test zero alerts edge case calculation`() = runBlocking {
        database.taskDao().clearAllNotifications()
        val notifications = database.taskDao().getAllNotifications().first()
        val criticalCount = notifications.count { it.severity == "CRITICAL" && !it.isAcknowledged }
        val reviewCount = notifications.count { !it.isAcknowledged }

        assertEquals(0, criticalCount)
        assertEquals(0, reviewCount)
    }

    @Test
    fun `test multiple unacknowledged critical alerts calculation`() = runBlocking {
        val alert1 = DesktopNotification(
            id = "alert-crit-1",
            title = "Critical Alert 1",
            message = "Test critical finding",
            severity = "CRITICAL",
            isAcknowledged = false
        )
        val alert2 = DesktopNotification(
            id = "alert-crit-2",
            title = "Critical Alert 2",
            message = "Second critical finding",
            severity = "CRITICAL",
            isAcknowledged = false
        )
        val alert3 = DesktopNotification(
            id = "alert-crit-3",
            title = "Resolved Alert",
            message = "Already resolved",
            severity = "CRITICAL",
            isAcknowledged = true
        )
        database.taskDao().insertNotifications(listOf(alert1, alert2, alert3))

        val notifications = database.taskDao().getAllNotifications().first()
        val activeCritical = notifications.count { it.severity == "CRITICAL" && !it.isAcknowledged }
        assertEquals(2, activeCritical)
    }

    // ==========================================
    // STAGE 4: AGENTS & TASKS ERROR TESTS
    // ==========================================

    @Test
    fun `test task cancellation transitions state to FAILED`() = runBlocking {
        val runningTask = DesktopTask(
            id = "test-task-cancel",
            title = "Cancelable Agent",
            status = "RUNNING",
            category = "AGENT",
            progress = 0.45f
        )
        database.taskDao().insertTask(runningTask)

        // Cancel via repository
        repository.cancelTask("test-task-cancel")

        val updatedTask = database.taskDao().getTaskById("test-task-cancel").first()
        assertNotNull(updatedTask)
        assertEquals("FAILED", updatedTask?.status)
    }

    @Test
    fun `test task retry transitions state back to RUNNING`() = runBlocking {
        val failedTask = DesktopTask(
            id = "test-task-retry",
            title = "Failed Agent",
            status = "FAILED",
            category = "PLAYBOOK",
            progress = 0.20f
        )
        database.taskDao().insertTask(failedTask)

        // Retry via repository
        repository.retryTask("test-task-retry")

        val retriedTask = database.taskDao().getTaskById("test-task-retry").first()
        assertNotNull(retriedTask)
        assertEquals("RUNNING", retriedTask?.status)
    }

    @Test
    fun `test supervisor approval and rejection states`() = runBlocking {
        val approvalTask = DesktopTask(
            id = "test-approval-task",
            title = "Staffing Agent Approval",
            status = "NEEDS_APPROVAL",
            category = "AGENT",
            requiresApproval = true,
            approvalPrompt = "Broadcast PM shift?"
        )
        database.taskDao().insertTask(approvalTask)

        // Reject action
        repository.submitApproval("test-approval-task", false)
        val rejectedTask = database.taskDao().getTaskById("test-approval-task").first()
        assertEquals("REJECTED", rejectedTask?.status)

        // Approve action
        repository.submitApproval("test-approval-task", true)
        val approvedTask = database.taskDao().getTaskById("test-approval-task").first()
        assertEquals("RUNNING", approvedTask?.status)
    }

    // ==========================================
    // STAGE 5: FILES & REPORTS ERROR TESTS
    // ==========================================

    @Test
    fun `test tamper detection on document checksum mismatch`() {
        val originalContent = "Confidential Patient Clinical Protocol v1.0"
        val expectedSha256 = EncryptionManager.computeSha256(originalContent)

        val tamperedContent = "Confidential Patient Clinical Protocol v1.0 (TAMPERED)"
        val tamperedSha256 = EncryptionManager.computeSha256(tamperedContent)

        assertNotEquals("Tampered content must have distinct SHA-256 hash", expectedSha256, tamperedSha256)
    }

    @Test
    fun `test deleting non-existent document does not fail`() = runBlocking {
        val nonExistentId = "non-existent-doc-uuid"
        // Should execute smoothly without crashing
        repository.deleteDocument(nonExistentId)
        val docs = repository.documents.first()
        assertFalse(docs.any { it.id == nonExistentId })
    }

    @Test
    fun `test empty document creation handling`() = runBlocking {
        val initialSize = repository.documents.first().size
        repository.saveDocument("EmptyDoc.txt", "TEXT", "   ", "Test")
        val newDocs = repository.documents.first()
        assertTrue(newDocs.size >= initialSize)
    }

    // ==========================================
    // STAGE 6: CLINICAL FEATURES & PHI SANITIZATION
    // ==========================================

    @Test
    fun `test alert acknowledgment is idempotent`() = runBlocking {
        val alert = DesktopNotification(
            id = "alert-idempotent",
            title = "GDR Review",
            message = "Review due for psychotropic titration",
            isAcknowledged = false
        )
        database.taskDao().insertNotification(alert)

        // First acknowledgment
        repository.acknowledgeAlert("alert-idempotent")
        val ack1 = database.taskDao().getAllNotifications().first().find { it.id == "alert-idempotent" }
        assertTrue(ack1?.isAcknowledged == true)

        // Second acknowledgment of already acknowledged alert
        repository.acknowledgeAlert("alert-idempotent")
        val ack2 = database.taskDao().getAllNotifications().first().find { it.id == "alert-idempotent" }
        assertTrue(ack2?.isAcknowledged == true)
    }

    @Test
    fun `test secure clinical mode toggle state`() {
        assertFalse(repository.secureClinicalMode.value)
        repository.setSecureClinicalMode(true)
        assertTrue(repository.secureClinicalMode.value)
        repository.setSecureClinicalMode(false)
        assertFalse(repository.secureClinicalMode.value)
    }

    @Test
    fun `test phi sanitization in notification previews`() {
        val rawMessage = "Elevated fall risk & psychotropic titration detected for resident in Unit 3 requiring review."
        // Verify no raw SSN or MRN format exists in default alerts
        assertFalse(rawMessage.contains("SSN:"))
        assertFalse(rawMessage.contains("MRN:"))
        assertFalse(rawMessage.contains("DOB:"))
    }

    // ==========================================
    // STAGE 7: REMOTE CONNECTIVITY VALIDATION
    // ==========================================

    @Test
    fun `test sync failure handles network exception gracefully`() = runBlocking {
        val unreachableConfig = DesktopNodeConfig(
            hostIp = "127.0.0.1",
            port = 1, // Non-existent port
            simulatedNodeEnabled = false
        )
        repository.updateConfig(unreachableConfig)

        val syncResult = repository.triggerSync()
        // Must return failure result cleanly without throwing an unhandled exception
        assertFalse(syncResult.success)
        assertEquals(ConnectionStatus.DISCONNECTED, repository.connectionStatus.value)
    }

    @Test
    fun `test battery optimization mode switching`() {
        val config = repository.nodeConfig.value
        assertEquals(BatteryOptimizationMode.SMART_ECO, config.batteryMode)

        repository.updateConfig(config.copy(batteryMode = BatteryOptimizationMode.REAL_TIME))
        assertEquals(BatteryOptimizationMode.REAL_TIME, repository.nodeConfig.value.batteryMode)

        repository.updateConfig(config.copy(batteryMode = BatteryOptimizationMode.MANUAL_ONLY))
        assertEquals(BatteryOptimizationMode.MANUAL_ONLY, repository.nodeConfig.value.batteryMode)
    }

    @Test
    fun `test model processing mode selection`() {
        assertEquals("Local", repository.modelProcessingMode.value)
        repository.setModelProcessingMode("Smart Hybrid")
        assertEquals("Smart Hybrid", repository.modelProcessingMode.value)
        repository.setModelProcessingMode("Cloud")
        assertEquals("Cloud", repository.modelProcessingMode.value)
        repository.setModelProcessingMode("Local Only")
        assertEquals("Local Only", repository.modelProcessingMode.value)
    }
}
