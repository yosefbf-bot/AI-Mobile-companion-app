package com.example.data.network

import android.util.Base64
import com.example.data.model.DesktopNodeConfig
import com.example.data.model.NodeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A consumed bootstrap attempt is never queued, persisted or automatically replayed. */
internal class BootstrapExchange(val config: DesktopNodeConfig, val scopes: Set<String>, val sharedChats: List<String>, val notificationsWhileLocked: Boolean) {
    override fun toString() = "BootstrapExchange(credentials omitted)"
}
class PairingBootstrapService(private val clock: () -> Long = System::currentTimeMillis) {
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private val cancellation = AtomicLong()
    private val attempted = linkedMapOf<String, Long>()
    fun cancelAll() { cancellation.incrementAndGet(); calls.forEach { it.cancel() } }

    internal suspend fun exchange(code: PairingBootstrap, previous: DesktopNodeConfig, authorized: () -> Unit): BootstrapExchange {
        val epoch = cancellation.get()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val check = {
            if (epoch != cancellation.get() || job?.isActive == false) throw CancellationException("Pairing was cancelled.")
            authorized()
        }
        val beforeSend = { check(); code.requireUnexpired(clock()) }
        currentCoroutineContext().ensureActive(); beforeSend()
        val attempt = MessageDigest.getInstance("SHA-256").digest((code.id + "|" + code.token).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        synchronized(attempted) {
            attempted.entries.removeAll { it.value <= System.currentTimeMillis() }
            if (attempted.containsKey(attempt)) throw GatewayException("PAIR_ATTEMPTED", message = "This pairing attempt was already used or interrupted. Generate a new code on the PC; do not retry the old code.")
            if (attempted.size >= 32) throw GatewayException("PAIR_LIMIT", message = "Too many recent pairing attempts. Wait for old codes to expire.")
            attempted[attempt] = code.expires
        }
        val presented = AtomicReference<X509Certificate?>()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificates are not used for pairing.") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    beforeSend()
                    require(chain.isNotEmpty())
                    val cert = chain[0]
                    PairingConfigParser.verifyCertificate(cert, code.host, code.fingerprint, java.util.Date(clock()))
                    presented.set(cert)
                } catch (_: Exception) { throw CertificateException("The secure pairing certificate or authorization could not be verified.") }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            // Default hostname/IP verification remains enabled, in addition to the handshake IP SAN check.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        val json = JSONObject().put("version", 2).put("id", code.id).put("token", code.token)
        val call = client.newCall(Request.Builder().url("https://${code.host}:${code.port}/mobile/api/v1/pair/exchange")
            .header("Host", "${code.host}:${code.port}").header("Accept", "application/json")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        calls.add(call)
        try {
            val output = suspendCancellableCoroutine<JSONObject> { continuation ->
                continuation.invokeOnCancellation { call.cancel(); calls.remove(call) }
                try { beforeSend() } catch (e: Exception) { calls.remove(call); continuation.resumeWithException(e); return@suspendCancellableCoroutine }
                if (!continuation.isActive) { call.cancel(); calls.remove(call); return@suspendCancellableCoroutine }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        calls.remove(call)
                        val failure = try {
                            // cancelAll can close the socket before coroutine cancellation
                            // reaches this callback. Preserve revocation/cancel semantics.
                            check()
                            GatewayException(if (e is javax.net.ssl.SSLException) "TLS" else "PAIR_INTERRUPTED", message =
                                if (e is javax.net.ssl.SSLException) "The PC certificate did not match the secure code. No pairing secret was sent to an unverified certificate." else "Pairing could not be confirmed. The PC may have consumed the code. Generate a new code on the PC before trying again.")
                        } catch (cancelled: CancellationException) { cancelled }
                        catch (denied: GatewayException) { denied }
                        catch (_: Exception) { GatewayException("PHONE_LOCKED", 423, "The protected workspace changed. Unlock before pairing again.") }
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                check()
                                if (response.code in 300..399) throw GatewayException("PAIR_REDIRECT", message = "Pairing redirects are not allowed. Use the code from the selected PC.")
                                val body = response.body ?: throw GatewayException("PAIR_PROTOCOL", message = "The PC returned no pairing response.")
                                val source = body.source()
                                val max = 32L * 1024
                                if (body.contentLength() > max || source.request(max + 1) && source.buffer.size > max) throw GatewayException("LIMIT", message = "The pairing response exceeded its size limit.")
                                val value = try { JSONObject(source.readUtf8()) } catch (_: Exception) { throw GatewayException("PAIR_PROTOCOL", message = "The PC returned an invalid pairing response.") }
                                if (!response.isSuccessful) {
                                    val status = response.code
                                    throw GatewayException("PAIR_DENIED", status, when (status) {
                                        401 -> "This pairing code is invalid, revoked or already used. Generate a new code on the PC."
                                        423 -> "Unlock Private AI on the PC before pairing."
                                        429 -> "The PC is busy or has too many pairings. Review Companion settings on the PC."
                                        else -> "The PC could not authorize this pairing. Check the gateway and generate a new code."
                                    })
                                }
                                check()
                                if (call.isCanceled()) throw CancellationException("Pairing was cancelled.")
                                if (continuation.isActive) continuation.resume(value)
                            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                            finally { calls.remove(call) }
                        }
                    }
                })
            }
            currentCoroutineContext().ensureActive(); check()
            return parseResponse(output, code, presented.get() ?: throw GatewayException("TLS", message = "No verified desktop certificate was received."), previous)
        } finally {
            call.cancel(); calls.remove(call)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    internal fun parseResponse(json: JSONObject, code: PairingBootstrap, cert: X509Certificate, previous: DesktopNodeConfig): BootstrapExchange {
        try {
            val allowed = setOf("version", "id", "name", "key", "notificationKey", "baseUrl", "certificate", "fingerprint", "expires", "scopes", "sharedChats", "notificationsWhileLocked")
            require(json.keys().asSequence().toSet().all { it in allowed })
            require(json.get("version") is Int && json.getInt("version") == 2)
            require(PairingBootstrapParser.deviceId(json.getString("id")))
            val name = json.getString("name")
            require(name.length in 1..100 && name.none { it.isISOControl() })
            require(json.getString("baseUrl") == "https://${code.host}:${code.port}")
            require(json.getString("fingerprint") == code.fingerprint)
            val der = json.getString("certificate")
            require(der.length in 100..12000 && Base64.decode(der, Base64.NO_WRAP).contentEquals(cert.encoded))
            require(json.get("expires") is String)
            val dateText = json.getString("expires")
            require(dateText.length in 20..40)
            // Node X509Certificate.validTo uses this GMT certificate date format.
            val format = java.text.SimpleDateFormat("MMM d HH:mm:ss yyyy 'GMT'", java.util.Locale.US).apply {
                isLenient = false; timeZone = java.util.TimeZone.getTimeZone("GMT")
            }
            val position = java.text.ParsePosition(0)
            require(format.parse(dateText, position)?.time == cert.notAfter.time && position.index == dateText.length)
            val scopeJson = json.getJSONObject("scopes")
            val known = setOf("chat", "tasks", "alerts", "workflow", "notifications")
            require(scopeJson.keys().asSequence().toSet().all { it in known })
            val scopes = scopeJson.keys().asSequence().filter { require(scopeJson.get(it) is Boolean); scopeJson.getBoolean(it) }.toSet()
            val chats = json.getJSONArray("sharedChats")
            require(chats.length() <= 100)
            val shared = (0 until chats.length()).map { chats.getString(it).also { id -> require(PairingBootstrapParser.deviceId(id)) } }
            require(shared.distinct().size == shared.size && (shared.isEmpty() || "chat" in scopes))
            require(json.get("notificationsWhileLocked") is Boolean)
            val locked = json.getBoolean("notificationsWhileLocked")
            val notification = json.optString("notificationKey", "")
            require(notification.isEmpty() || Regex("[A-Za-z0-9_-]{43}").matches(notification) && "notifications" in scopes)
            require(!locked || "notifications" in scopes && notification.isNotEmpty())
            val config = previous.copy(hostIp = code.host, port = code.port, scheme = "https", pairKey = json.getString("key"),
                friendlyName = name, certificateDer = der, certificateFingerprint = code.fingerprint,
                simulatedNodeEnabled = false, notificationKey = notification, backgroundNotificationsEnabled = false)
            PairingConfigParser.certificate(config)
            return BootstrapExchange(config, scopes, shared, locked)
        } catch (_: Exception) { throw GatewayException("PAIR_PROTOCOL", message = "The PC pairing response did not match the verified code or approved permissions. Generate a new code; the previous pairing is unchanged.") }
    }

    internal suspend fun verifyStatus(config: DesktopNodeConfig, authorized: () -> Unit): NodeStats {
        val service = DesktopNodeService { candidate ->
            if (candidate != config) null else try { authorized(); GatewayAuthorizationEpoch() } catch (_: Exception) { null }
        }
        try {
            authorized()
            val json = service.request(config, GatewayEndpoints.STATUS)
            require(json.optString("serverName").isNotBlank())
            val stats = DesktopNodeService.parseStatus(json, config)
            authorized(); currentCoroutineContext().ensureActive()
            return stats
        } finally { service.close() }
    }
}
