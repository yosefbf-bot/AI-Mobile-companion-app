package com.example.data.network

import com.example.data.crypto.EncryptionManager
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.local.entity.EncryptedDocument
import com.example.data.model.DesktopNodeConfig
import com.example.data.model.NodeStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit





internal class DemoNodeService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Checks if the Private AI Companion Gateway (/mobile/api/v1/status) is reachable on LAN/VPN.
     */
    suspend fun checkHealth(config: DesktopNodeConfig): Boolean = withContext(Dispatchers.IO) {
        if (config.simulatedNodeEnabled) {
            return@withContext true
        }

        return@withContext try {
            val url = "https://${config.hostIp}:${config.port}${GatewayEndpoints.STATUS}"
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Pair-Key", config.pairKey)
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            // If physical PC is offline or in airgapped testing, return true if simulated mode or fallback
            config.simulatedNodeEnabled
        }
    }

    /**
     * Fetches current desktop hardware & model status (CPU, GPU, VRAM, loaded model)
     */
    suspend fun getNodeStats(config: DesktopNodeConfig): NodeStats = withContext(Dispatchers.IO) {
        NodeStats(
            serverName = config.friendlyName,
            loadedModel = "Llama-3.3-70B-Instruct-Q4 (Local Workstation)",
            cpuUsagePercent = (15..32).random(),
            gpuUsagePercent = (35..78).random(),
            vramUsedGb = 16.4f,
            vramTotalGb = 24.0f,
            activeJobsCount = 2,
            serverUptime = "4d 18h 35m",
            localIp = "${config.hostIp}:${config.port}",
            protocolVersion = "Gateway-v1-E2EE"
        )
    }

    /**
     * Streams inference tokens in real-time from the workstation node.
     * Android does NOT call OpenAI or external clouds; Windows Private AI processes and routes.
     */
    fun streamInferenceFromDesktop(
        prompt: String,
        attachmentName: String? = null,
        attachmentType: String? = null
    ): Flow<String> = flow {
        val fullAnswer = generateDesktopAIResponse(prompt, attachmentName, attachmentType)
        val tokens = fullAnswer.split(" ")

        for (i in tokens.indices) {
            delay((25..55).random().toLong()) // Snappy local token emission
            emit(tokens.take(i + 1).joinToString(" "))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sends prompt to desktop node for full inference.
     */
    suspend fun dispatchInferenceToDesktop(
        sessionId: String,
        userPrompt: String,
        config: DesktopNodeConfig,
        attachmentName: String? = null,
        attachmentType: String? = null
    ): EncryptedChatMessage = withContext(Dispatchers.IO) {
        delay(900)

        val answerText = generateDesktopAIResponse(userPrompt, attachmentName, attachmentType)
        val encryptedAnswer = EncryptionManager.encrypt(answerText)

        val modelUsed = if (attachmentType == "IMAGE") {
            "Llama-3.2-Vision (Workstation Local)"
        } else {
            "Llama-3.3-70B-Instruct (Desktop Node)"
        }

        EncryptedChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sender = "assistant",
            encryptedContent = encryptedAnswer,
            timestamp = System.currentTimeMillis(),
            status = "SYNCED_DESKTOP",
            promptTokens = userPrompt.length / 4 + (if (attachmentName != null) 384 else 12),
            completionTokens = answerText.length / 4 + 24,
            modelName = modelUsed
        )
    }

    /**
     * Pulls latest delta sync from desktop node
     */
    suspend fun pullDesktopSync(config: DesktopNodeConfig): DesktopSyncPackage = withContext(Dispatchers.IO) {
        delay(500)
        DesktopSyncPackage(
            newNotifications = listOf(
                DesktopNotification(
                    id = "alert-1",
                    title = "Critical Clinical Finding",
                    message = "Elevated fall risk & psychotropic titration detected for resident in Unit 3 requiring review.",
                    timestamp = System.currentTimeMillis() - 15 * 60_000,
                    severity = "CRITICAL",
                    category = "CLINICAL",
                    requiresApproval = false,
                    assignedTo = "Director of Nursing",
                    relatedAction = "Review Medication Orders"
                ),
                DesktopNotification(
                    id = "alert-2",
                    title = "Agent Requires Approval",
                    message = "Staffing Analysis Agent proposes automated shift-fill broadcast to float pool.",
                    timestamp = System.currentTimeMillis() - 42 * 60_000,
                    severity = "HIGH",
                    category = "AGENT_APPROVAL",
                    requiresApproval = true,
                    assignedTo = "Administrator",
                    relatedAction = "Approve Shift Broadcast"
                ),
                DesktopNotification(
                    id = "alert-3",
                    title = "Daily Surveillance Finding",
                    message = "Surveillance algorithm detected 3 positive respiratory panels in West Wing within 36 hours.",
                    timestamp = System.currentTimeMillis() - 95 * 60_000,
                    severity = "HIGH",
                    category = "SURVEILLANCE",
                    requiresApproval = false,
                    assignedTo = "Infection Preventionist",
                    relatedAction = "Run Contact Cluster Trace"
                ),
                DesktopNotification(
                    id = "alert-4",
                    title = "Antipsychotic Review Follow-up",
                    message = "Quarterly Gradual Dose Reduction (GDR) eligibility review ready for 5 residents.",
                    timestamp = System.currentTimeMillis() - 180 * 60_000,
                    severity = "MEDIUM",
                    category = "OPERATIONAL",
                    requiresApproval = false,
                    assignedTo = "Attending Physician",
                    relatedAction = "Open GDR Summary"
                )
            ),
            updatedTasks = listOf(
                DesktopTask(
                    id = "task-surveillance",
                    title = "Clinical Surveillance",
                    status = "COMPLETED",
                    category = "SURVEILLANCE",
                    progress = 1.0f,
                    modelName = "Llama-3.3-70B-Clinical",
                    startedAt = System.currentTimeMillis() - 7200_000,
                    completedAt = System.currentTimeMillis() - 1200_000,
                    resultSummary = "Analyzed 128 resident EHR updates. Flagged 2 high-priority trends with zero cloud leak."
                ),
                DesktopTask(
                    id = "task-weekly",
                    title = "Weekly Operations Review",
                    status = "RUNNING",
                    category = "OPERATIONS",
                    progress = 0.68f,
                    modelName = "Llama-3.3-70B-Operations",
                    startedAt = System.currentTimeMillis() - 1800_000,
                    resultSummary = "Compiling census variance and labor overtime metrics across 4 facilities."
                ),
                DesktopTask(
                    id = "task-morning",
                    title = "Morning Administrator Brief",
                    status = "COMPLETED",
                    category = "AGENT",
                    progress = 1.0f,
                    modelName = "Llama-3.3-70B-Executive",
                    startedAt = System.currentTimeMillis() - 14400_000,
                    completedAt = System.currentTimeMillis() - 13800_000,
                    resultSummary = "Synthesized clinical incidents, admissions, and compliance flags into executive summary."
                ),
                DesktopTask(
                    id = "task-antipsychotic",
                    title = "Antipsychotic Review Playbook",
                    status = "RUNNING",
                    category = "PLAYBOOK",
                    progress = 0.45f,
                    modelName = "Llama-3.3-70B-Clinical",
                    startedAt = System.currentTimeMillis() - 600_000,
                    resultSummary = "Cross-referencing behavioral logs with psychotropic administration records."
                ),
                DesktopTask(
                    id = "task-staffing",
                    title = "Staffing Analysis Agent",
                    status = "NEEDS_APPROVAL",
                    category = "AGENT",
                    progress = 0.85f,
                    modelName = "Private-AI-Agent",
                    startedAt = System.currentTimeMillis() - 2400_000,
                    requiresApproval = true,
                    approvalPrompt = "Broadcast available weekend PM CNA shifts to pre-qualified float pool?"
                )
            )
        )
    }

    suspend fun startAgent(name: String, config: DesktopNodeConfig): DesktopTask = withContext(Dispatchers.IO) {
        delay(500)
        DesktopTask(
            id = "agent-${System.currentTimeMillis()}",
            title = name,
            status = "RUNNING",
            category = "AGENT",
            progress = 0.15f,
            modelName = "Private-AI Workstation Agent",
            startedAt = System.currentTimeMillis(),
            resultSummary = "Agent initialized on Windows host. Scanning environment and executing playbook steps."
        )
    }

    suspend fun runPlaybook(name: String, config: DesktopNodeConfig): DesktopTask = withContext(Dispatchers.IO) {
        delay(500)
        DesktopTask(
            id = "playbook-${System.currentTimeMillis()}",
            title = name,
            status = "RUNNING",
            category = "PLAYBOOK",
            progress = 0.10f,
            modelName = "Llama-3.3-70B-Clinical",
            startedAt = System.currentTimeMillis(),
            resultSummary = "Playbook pipeline started on desktop node. Processing rules against local database."
        )
    }

    suspend fun cancelTask(taskId: String, config: DesktopNodeConfig): Boolean = withContext(Dispatchers.IO) {
        delay(250)
        true
    }

    suspend fun retryTask(taskId: String, config: DesktopNodeConfig): Boolean = withContext(Dispatchers.IO) {
        delay(300)
        true
    }

    suspend fun submitApproval(taskId: String, approved: Boolean, config: DesktopNodeConfig): Boolean = withContext(Dispatchers.IO) {
        delay(300)
        true
    }

    private fun generateDesktopAIResponse(
        prompt: String,
        attachmentName: String? = null,
        attachmentType: String? = null
    ): String {
        val lowerPrompt = prompt.lowercase()

        if (attachmentName != null) {
            return "### Workstation Analysis: $attachmentName\n\n" +
                    "**Processing Mode**: Local GPU Tensor Core\n" +
                    "**Status**: Complete (Zero cloud transit)\n\n" +
                    "1. Document structure extracted and indexed into local vector database.\n" +
                    "2. Cross-referenced against compliance guidelines and clinical protocols.\n" +
                    "3. Key entities, medication orders, and dates validated."
        }

        return when {
            lowerPrompt.contains("surveillance") || lowerPrompt.contains("infection") -> {
                "### Daily Clinical Surveillance Summary\n\n" +
                        "• **Surveillance Window**: Last 24 Hours\n" +
                        "• **Units Monitored**: 4 Skilled Nursing Units (142 residents)\n" +
                        "• **Findings**: 2 residents flagged for early respiratory symptoms (Room 214, Room 302).\n" +
                        "• **Protocol Action**: Infection Preventionist notified; cohort isolation protocols staged on desktop."
            }
            lowerPrompt.contains("antipsychotic") || lowerPrompt.contains("gdr") -> {
                "### Antipsychotic & GDR Review\n\n" +
                        "• **Compliance Benchmark**: CMS F-758 Regulatory Standards\n" +
                        "• **Identified Candidates**: 3 residents meeting 6-month stability criteria for Gradual Dose Reduction (GDR).\n" +
                        "• **Behavioral Baseline**: Adverse symptom episodes < 1/month. Attending physician notification drafted."
            }
            lowerPrompt.contains("brief") || lowerPrompt.contains("morning") -> {
                "### Morning Administrator Brief\n\n" +
                        "• **Census**: 94.2% Occupancy (132/140 beds)\n" +
                        "• **Staffing Ratio**: 3.85 HPRD (Compliant with State & Federal mandates)\n" +
                        "• **Clinical Escalations**: 1 fall with zero injury; 2 hospital returns cleared.\n" +
                        "• **Open Approvals**: 1 float pool shift dispatch pending administrator signoff."
            }
            lowerPrompt.contains("zero-trust") || lowerPrompt.contains("encrypt") || lowerPrompt.contains("security") -> {
                "### Private AI Security Architecture\n\n" +
                        "1. **Local Host Principle**: Windows Private AI is the brain and primary storage.\n" +
                        "2. **Companion Gateway**: Exposes only restricted `/mobile/api/v1/` routes.\n" +
                        "3. **Keystore Pinning**: Android private keys never leave device hardware Keystore.\n" +
                        "4. **No Third-Party Cloud**: OpenAI keys reside strictly on your Windows workstation."
            }
            else -> {
                "### Private AI Workstation Response\n\n" +
                        "Synthesized by local model running on your Windows workstation.\n\n" +
                        "• **Zero Data Leak**: Your prompt and attachments never touch external third-party cloud servers.\n" +
                        "• **Gateway Channel**: Routed via encrypted Companion Gateway (`/mobile/api/v1/`).\n" +
                        "• **Audit Trail**: Action recorded in workstation tamper-evident local audit log."
            }
        }
    }
}
