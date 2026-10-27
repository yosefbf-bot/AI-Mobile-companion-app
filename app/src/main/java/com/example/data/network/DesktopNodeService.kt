package com.example.data.network

import android.util.Base64
import com.example.data.local.entity.DesktopNotification
import com.example.data.local.entity.DesktopTask
import com.example.data.model.DesktopNodeConfig
import com.example.data.model.NodeStats
import com.example.data.model.FollowUpNotification
import com.example.data.model.AlertCoverage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GatewayEndpoints {
    const val STATUS = "/mobile/api/v1/status"
    const val CHAT_SEND = "/mobile/api/v1/chat/send"
    const val TASKS = "/mobile/api/v1/tasks"
    const val ALERTS = "/mobile/api/v1/alerts"
}

data class DesktopSyncPackage(val newNotifications: List<DesktopNotification>, val updatedTasks: List<DesktopTask>, val alertCoverage: AlertCoverage = AlertCoverage())
data class GatewayReply(val reply: String, val sessionId: String, val requestId: String?, val provider: String?, val model: String?, val incomplete: Boolean,
    val userMessageId: String? = null, val assistantMessageId: String? = null)
class GatewayException(val code: String, val httpStatus: Int = 0, message: String) : IOException(message)

data class GatewayAuthorizationEpoch(val authentication: Long = 0, val workspace: Long = 0)
class GatewayRequestLease internal constructor(internal val epoch: GatewayAuthorizationEpoch, internal val cancellation: Long)

class DesktopNodeService(
    private val elapsedMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val authorizationEpoch: (DesktopNodeConfig) -> GatewayAuthorizationEpoch? = { GatewayAuthorizationEpoch() }
) {
    private val demo = DemoNodeService()
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private val cancellationGeneration = AtomicLong()
    private val requestBudget = Mutex()
    private val requestTimes = java.util.ArrayDeque<Long>()
    @Volatile private var retryNotBefore = 0L

    fun retryDelayMillis(): Long = (retryNotBefore - elapsedMillis()).coerceAtLeast(0)

    private suspend fun reserveRequest(config: DesktopNodeConfig, lease: GatewayRequestLease) {
        while (true) {
            currentCoroutineContext().ensureActive()
            requireAuthorized(config, lease)
            if (retryDelayMillis() > 0) throw GatewayException("RATE_LIMITED", 429, errorMessage("RATE_LIMITED", 429))
            val wait = requestBudget.withLock {
                val now = elapsedMillis()
                while (requestTimes.isNotEmpty() && now - requestTimes.peekFirst() >= 60_000L) requestTimes.removeFirst()
                // Leave room below the gateway's 120/minute limit for pairing and other clients.
                if (requestTimes.size < 100) { requestTimes.addLast(now); 0L }
                else (60_000L - (now - requestTimes.peekFirst())).coerceAtLeast(1)
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    fun cancelAll() { cancellationGeneration.incrementAndGet(); calls.forEach { it.cancel() } }

    fun authorizationLease(config: DesktopNodeConfig): GatewayRequestLease {
        val epoch = authorizationEpoch(config) ?: throw GatewayException("PHONE_LOCKED",423,"Unlock the protected phone workspace before continuing.")
        return GatewayRequestLease(epoch, cancellationGeneration.get())
    }
    fun requireAuthorization(config: DesktopNodeConfig, lease: GatewayRequestLease) = requireAuthorized(config, lease)
    fun close() {
        cancelAll()
        synchronized(this) {
            cachedClient?.connectionPool?.evictAll()
            cachedClient?.dispatcher?.executorService?.shutdown()
            cachedClient = null
            cachedDestination = null
        }
    }
    private fun requireAuthorized(config: DesktopNodeConfig, lease: GatewayRequestLease) {
        if (authorizationEpoch(config) != lease.epoch) throw GatewayException("PHONE_LOCKED",423,"The protected workspace changed. Unlock and retry the retained request.")
        if (cancellationGeneration.get() != lease.cancellation) throw CancellationException("The desktop request was stopped.")
    }

    private var cachedClient: OkHttpClient? = null
    private var cachedDestination: String? = null

    @Synchronized
    private fun client(config: DesktopNodeConfig, chat: Boolean = false): OkHttpClient {
        val cert = try { PairingConfigParser.certificate(config) } catch (_: Exception) {
            throw GatewayException("PAIRING_REQUIRED", message = "Pair again with the desktop's complete HTTPS link and certificate.")
        }
        val destination = "${config.hostIp}:${config.port}:${PairingConfigParser.destinationIdentity(config)}"
        cachedClient?.takeIf { cachedDestination == destination }?.let {
            return if (chat) it.newBuilder().readTimeout(330, TimeUnit.SECONDS).callTimeout(335, TimeUnit.SECONDS).build() else it
        }
        cachedClient?.connectionPool?.evictAll()
        cachedClient?.dispatcher?.executorService?.shutdown()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("paired-desktop", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val base = OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            // Keep ordinary hostname/IP verification. Never accept all self-signed certificates.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .build()
        cachedDestination = destination
        cachedClient = base
        return if (chat) base.newBuilder().readTimeout(330, TimeUnit.SECONDS).callTimeout(335, TimeUnit.SECONDS).build() else base
    }

    suspend fun request(config: DesktopNodeConfig, endpoint: String, data: JSONObject? = null,
        lease: GatewayRequestLease = authorizationLease(config)): JSONObject {
        currentCoroutineContext().ensureActive()
        requireAuthorized(config, lease)
        if (config.simulatedNodeEnabled) throw GatewayException("DEMO", message = "Demo mode cannot contact the desktop.")
        require(endpoint.startsWith("/mobile/api/v1/") && !endpoint.contains("?") && !endpoint.contains("#"))
        reserveRequest(config, lease)
        requireAuthorized(config, lease)
        val securedClient = client(config, endpoint == GatewayEndpoints.CHAT_SEND)
        val request = Request.Builder().url("https://${config.hostIp}:${config.port}$endpoint")
            .header("Host", "${config.hostIp}:${config.port}")
            .header("X-Pair-Key", config.pairKey).header("Accept", "application/json")
        if (data != null) request.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = securedClient.newCall(request.build())
        calls.add(call)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel(); calls.remove(call) }
            try {
                requireAuthorized(config, lease)
                if (!continuation.isActive) { call.cancel(); calls.remove(call); return@suspendCancellableCoroutine }
            } catch (e: Exception) {
                call.cancel(); calls.remove(call)
                if (continuation.isActive) continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(call)
                    if (continuation.isActive) continuation.resumeWithException(
                        GatewayException(if (e is javax.net.ssl.SSLException) "TLS" else "OFFLINE", message =
                            if (e is javax.net.ssl.SSLException) "The paired desktop certificate could not be verified. Pair again." else "Desktop unavailable or request interrupted. Your pending request is retained.")
                    )
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            requireAuthorized(config, lease)
                            val body = response.body ?: throw GatewayException("PROTOCOL", message = "Desktop returned no response.")
                            val max = if (endpoint.endsWith("/evidence/read")) 8L * 1024 * 1024 else 1024L * 1024
                            val source = body.source()
                            if (body.contentLength() > max || source.request(max + 1) && source.buffer.size > max) throw GatewayException("LIMIT", message = "Desktop response exceeded the allowed size.")
                            val json = try { JSONObject(source.readUtf8()) } catch (_: Exception) { throw GatewayException("PROTOCOL", message = "Desktop returned an invalid response.") }
                            if (!response.isSuccessful) {
                                val code = json.optJSONObject("error")?.optString("code")?.takeIf { it.matches(Regex("[A-Z0-9_]{1,60}")) } ?: "HTTP_${response.code}"
                                if (response.code == 429) {
                                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
                                    synchronized(this@DesktopNodeService) {
                                        retryNotBefore = maxOf(retryNotBefore, elapsedMillis() + seconds * 1000)
                                    }
                                }
                                throw GatewayException(code, response.code, errorMessage(code, response.code))
                            }
                            requireAuthorized(config, lease)
                            if (call.isCanceled()) throw CancellationException("The desktop request was stopped.")
                            if (continuation.isActive) continuation.resume(json)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                        finally { calls.remove(call) }
                    }
                }
            })
        }
    }

    suspend fun checkHealth(config: DesktopNodeConfig): Boolean = try {
        if (config.simulatedNodeEnabled) false else getNodeStats(config).serverName.isNotBlank()
    } catch (_: Exception) { false }

    suspend fun getNodeStats(config: DesktopNodeConfig): NodeStats {
        if (config.simulatedNodeEnabled) return NodeStats(serverName = "DEMO — isolated phone workspace", loadedModel = "No desktop model is running", localIp = "Demo", protocolVersion = "Demo")
        return parseStatus(request(config, GatewayEndpoints.STATUS), config)
    }

    suspend fun dispatchInferenceToDesktop(sessionId: String, userPrompt: String, config: DesktopNodeConfig, requestId: String, requireLocal: Boolean = true, requireHipaa: Boolean = true): GatewayReply {
        if (config.simulatedNodeEnabled) {
            val answer = StringBuilder()
            demo.streamInferenceFromDesktop(userPrompt).collect { answer.clear(); answer.append(it) }
            return GatewayReply("DEMO — synthetic example; no desktop work performed.\n\n$answer", sessionId, requestId, null, "Demo", false)
        }
        val json = request(config, GatewayEndpoints.CHAT_SEND, chatRequest(sessionId,userPrompt,requestId,requireLocal,requireHipaa))
        val reply = json.optString("reply")
        if (reply.isBlank() || json.optString("sessionId") != sessionId || json.optString("requestId") != requestId) throw GatewayException("PROTOCOL", message = "Desktop acknowledgment does not match this request.")
        val processing = json.optJSONObject("processing")
        val userId = nullableString(json, "userMessageId")
        val answerId = nullableString(json, "assistantMessageId")
        if ((userId == null) != (answerId == null) || userId != null &&
            (userId == answerId || !userId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !answerId!!.matches(Regex("[A-Za-z0-9_-]{1,128}"))))
            throw GatewayException("PROTOCOL", message = "Desktop receipt linkage is invalid.")
        return GatewayReply(reply, sessionId, requestId, nullableString(processing, "provider"), nullableString(processing, "model"), json.optBoolean("incomplete"), userId, answerId)
    }

    suspend fun pullDesktopSync(config: DesktopNodeConfig, stats: NodeStats): DesktopSyncPackage {
        if (config.simulatedNodeEnabled) {
            val rows = demo.pullDesktopSync(config)
            return DesktopSyncPackage(rows.newNotifications.map { it.copy(title = "DEMO — ${it.title}", message = "Synthetic example. No clinical finding or action occurred.\n${it.message}") },
                rows.updatedTasks.map { it.copy(title = "DEMO — ${it.title}", resultSummary = "Synthetic demonstration; no desktop task executed.", modelName = "Demo") })
        }
        val lease = authorizationLease(config)
        val tasks = if ("tasks" in stats.scopes) parseTasks(request(config, GatewayEndpoints.TASKS, lease = lease)) else emptyList()
        val alerts = if ("alerts" in stats.scopes) request(config, GatewayEndpoints.ALERTS, lease = lease) else null
        return DesktopSyncPackage(alerts?.let(::parseAlerts) ?: emptyList(), tasks,
            alerts?.let(::parseAlertCoverage) ?: AlertCoverage(scopeGranted = false))
    }

    suspend fun fetchNotifications(config: DesktopNodeConfig): List<FollowUpNotification> {
        val array = request(config, "/mobile/api/v1/notifications").getJSONArray("notifications")
        return (0 until array.length()).map { i -> val row = array.getJSONObject(i)
            FollowUpNotification(row.getString("id"), if (row.isNull("actionId")) null else row.getString("actionId"), row.getLong("created"), row.getString("status")) }
    }
    suspend fun acknowledgeNotification(config: DesktopNodeConfig, id: String) {
        val reply = request(config, "/mobile/api/v1/notifications/ack", JSONObject().put("id", id))
        require(reply.getString("id") == id && reply.getString("status") == "acknowledged") { "Notification receipt was not confirmed." }
    }

    suspend fun startAgent(name: String, config: DesktopNodeConfig): DesktopTask {
        if (!config.simulatedNodeEnabled) unsupported("Agent execution")
        return demo.startAgent(name, config).copy(title = "DEMO — $name", modelName = "Demo", resultSummary = "Synthetic demonstration; no task was dispatched.")
    }
    suspend fun runPlaybook(name: String, config: DesktopNodeConfig): DesktopTask {
        if (!config.simulatedNodeEnabled) unsupported("Playbook execution")
        return demo.runPlaybook(name, config).copy(title = "DEMO — $name", modelName = "Demo", resultSummary = "Synthetic demonstration; no playbook was executed.")
    }
    suspend fun cancelTask(taskId: String, config: DesktopNodeConfig) { if (!config.simulatedNodeEnabled) unsupported("Report-job cancellation") }
    suspend fun retryTask(taskId: String, config: DesktopNodeConfig) { if (!config.simulatedNodeEnabled) unsupported("Report-job retry") }
    suspend fun submitApproval(taskId: String, approved: Boolean, config: DesktopNodeConfig) { if (!config.simulatedNodeEnabled) unsupported("Remote approval") }

    companion object {
        fun chatRequest(sessionId: String, prompt: String, requestId: String, requireLocal: Boolean, requireHipaa: Boolean): JSONObject {
            if (prompt.isBlank() || prompt.length > 12000) throw GatewayException("LIMIT",422,"This message is too long for the desktop. Send shorter sections of up to 12,000 characters.")
            val input = JSONObject().put("prompt",prompt).put("sessionId",sessionId).put("requestId",requestId)
                .put("requireLocal",requireLocal).put("requireHipaa",requireHipaa)
            if (input.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024) throw GatewayException("LIMIT",422,"This message is too large for the desktop. Send shorter sections.")
            return input
        }
        fun unsupported(name: String): Nothing = throw GatewayException("UNSUPPORTED", message = "$name is not exposed by the paired desktop. Review it on the PC.")
        private fun nullableString(json: JSONObject?, key: String): String? = json?.takeUnless { it.isNull(key) }?.optString(key)?.takeIf { it.isNotBlank() }
        fun parseStatus(json: JSONObject, config: DesktopNodeConfig): NodeStats {
            if (json.optString("status") != "ONLINE" || json.optInt("protocolVersion") != 1) throw GatewayException("PROTOCOL", message = "Unsupported desktop gateway status.")
            val scopes = json.optJSONObject("scopes") ?: JSONObject()
            val caps = json.optJSONArray("capabilities")
            return NodeStats(serverName = json.optString("serverName", "Private AI desktop"),
                loadedModel = nullableString(json, "loadedModel") ?: "No local model loaded",
                cpuUsagePercent = if (json.isNull("cpuUsage")) null else json.optInt("cpuUsage"),
                gpuUsagePercent = if (json.isNull("gpuUsage")) null else json.optInt("gpuUsage"),
                vramUsedGb = if (json.isNull("vramUsedGb")) null else json.optDouble("vramUsedGb").toFloat(),
                vramTotalGb = if (json.isNull("vramTotalGb")) null else json.optDouble("vramTotalGb").toFloat(),
                activeJobsCount = if (json.isNull("activeJobs")) null else json.optInt("activeJobs"),
                serverUptime = json.optString("uptime", "Unknown"), localIp = "${config.hostIp}:${config.port}", protocolVersion = "Gateway v1",
                localModelState = json.optString("localModelState", "unknown"), processingMode = json.optString("processingMode", "Unknown"), provider = json.optString("provider", "Unknown"),
                scopes = scopes.keys().asSequence().filter { scopes.optBoolean(it) }.toSet(),
                capabilities = if (caps == null) emptySet() else (0 until caps.length()).map { caps.getString(it) }.toSet())
        }
        fun parseTasks(json: JSONObject): List<DesktopTask> {
            val array = json.getJSONArray("tasks")
            return (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                DesktopTask(id = row.getString("id"), title = "Report job ${row.getString("id").take(8)}",
                    status = row.getString("status").uppercase(), category = "REPORT", modelName = "Not provided",
                    startedAt = if (row.isNull("created")) 0 else row.getLong("created"), resultSummary = "Status only. Review results and approvals on the desktop.")
            }
        }
        fun parseAlerts(json: JSONObject): List<DesktopNotification> {
            val array = json.getJSONArray("alerts")
            return (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                DesktopNotification(id = row.getString("id"), title = "Clinical alert — review on desktop",
                    message = "Category: ${row.optString("category", "Unknown")}. Status: ${row.optString("status", "Unknown")}. Metadata only; an empty list is not an all-clear.",
                    timestamp = 0, severity = row.optString("severity", "INFO").uppercase(), category = "CLINICAL")
            }
        }
        fun parseAlertCoverage(json: JSONObject) = AlertCoverage(
            scopeGranted = true,
            monitoringEnabled = json.opt("monitoringEnabled") as? Boolean,
            isSurveillanceComplete = json.opt("isSurveillanceComplete") as? Boolean,
            total = when (val total = json.opt("total")) {
                is Int -> total.takeIf { it >= 0 }
                is Long -> total.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            },
            truncated = json.opt("truncated") as? Boolean
        )
        internal fun errorMessage(code: String, status: Int): String = when {
            status == 401 -> "This phone is unpaired or its access was revoked. Pair again on the desktop."
            status == 423 -> "The desktop is locked. Unlock it before using the companion."
            status == 403 -> "The paired phone does not have permission for this operation."
            status == 429 -> "Desktop request limit reached. Wait before retrying."
            code == "COMPANION_PAUSED" -> "Companion processing is paused on the PC. Resume it in desktop Settings; this request remains pending."
            code in setOf("LOW_MEMORY", "MEMORY_PRESSURE", "INSUFFICIENT_MEMORY") -> "The PC has too little free memory. Close other workloads or unload a model on the PC, then retry."
            code in setOf("SPEECH_SETUP", "SPEECH_SETUP_REQUIRED", "SPEECH_UNAVAILABLE", "MEETING_SPEECH_UNAVAILABLE") -> "Set up the verified offline speech runtime and model in desktop Meetings settings, then retry. Saved recordings remain on this phone."
            code == "INTERRUPTED" -> "Desktop execution was interrupted. Review the conversation before deciding to send a new request."
            code == "CHANGED" -> "This request ID was already used with different contents."
            code == "BUSY" -> "Desktop is busy. This request remains pending."
            code == "COMPANION_ATTACHMENT" -> "Use a reviewed note with evidence; chat uploads are not supported."
            else -> "Desktop could not finish this request ($code). No success was recorded."
        }
    }
}
