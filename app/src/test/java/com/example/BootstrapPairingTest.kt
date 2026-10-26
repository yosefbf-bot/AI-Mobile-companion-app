package com.example

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.repository.PrivateAIRepository
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
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
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import javax.crypto.KeyGenerator
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Real loopback HTTPS and encrypted Room/config; exclusively public synthetic test credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootstrapPairingTest {
    private lateinit var server: HttpsServer
    private lateinit var repository: PrivateAIRepository
    private lateinit var database: AppDatabase
    private lateinit var storage: SecureConfigStore
    private lateinit var cipher: VaultCipher
    private lateinit var previous: DesktopNodeConfig
    private lateinit var certificate: X509Certificate
    private val executor = Executors.newFixedThreadPool(3)
    private val exchanges = AtomicInteger()
    private val statusReads = AtomicInteger()
    private val chatRequests = AtomicInteger()
    @Volatile private var beforeExchange: (() -> Unit)? = null
    @Volatile private var beforeSave: (() -> Unit)? = null
    @Volatile private var beforeDecrypt: (() -> Unit)? = null
    @Volatile private var dropResponse = false
    @Volatile private var redirect = false
    @Volatile private var denyStatus = false
    @Volatile private var statusOnly = false
    @Volatile private var mutate: ((JSONObject) -> Unit)? = null
    private val inviteId = UUID.randomUUID().toString()
    private val deviceId = UUID.randomUUID().toString()
    private val token = "T".repeat(43)
    private val newKey = "B".repeat(43)
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun scopes() = JSONObject().put("chat",!statusOnly).put("workflow",!statusOnly).put("tasks",false).put("alerts",false).put("notifications",false)
    private fun link(host: String = "127.0.0.1", pin: String = fingerprint(certificate), expires: Long = System.currentTimeMillis()+180_000) =
        "privateai://pair/v2?version=2&host=$host&port=${server.address.port}&fingerprint=$pin&id=$inviteId&token=$token&expires=$expires"
    private fun startServer(host: String = "127.0.0.1") {
        val keys = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/synthetic-gateway-test.p12")!!.use { keys.load(it,"test-fixture".toCharArray()) }
        certificate = keys.getCertificate("synthetic") as X509Certificate
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"test-fixture".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers,null,null) }
        server = HttpsServer.create(InetSocketAddress(host,0),0).apply { httpsConfigurator=HttpsConfigurator(ssl); this.executor=this@BootstrapPairingTest.executor }
        server.createContext("/mobile/api/v1/") { exchange ->
            val endpoint = exchange.requestURI.path
            var status = 200
            val output = when (endpoint) {
                "/mobile/api/v1/pair/exchange" -> {
                    exchanges.incrementAndGet()
                    assertEquals("POST",exchange.requestMethod)
                    val input = JSONObject(exchange.requestBody.bufferedReader().readText())
                    assertEquals(setOf("version","id","token"),input.keys().asSequence().toSet())
                    assertEquals(2,input.getInt("version"));assertEquals(inviteId,input.getString("id"));assertEquals(token,input.getString("token"))
                    beforeExchange?.invoke()
                    if (dropResponse) { exchange.close();return@createContext }
                    if (redirect) { status=307;exchange.responseHeaders.add("Location","https://127.0.0.1:1/never-follow") }
                    JSONObject().put("version",2).put("id",deviceId).put("name","Synthetic approved phone")
                        .put("key",newKey).put("baseUrl","https://$host:${server.address.port}")
                        .put("certificate",Base64.encodeToString(certificate.encoded,Base64.NO_WRAP)).put("fingerprint",fingerprint(certificate))
                        .put("expires",java.text.SimpleDateFormat("MMM d HH:mm:ss yyyy 'GMT'",java.util.Locale.US).apply { timeZone=java.util.TimeZone.getTimeZone("GMT") }.format(certificate.notAfter)).put("scopes",scopes()).put("sharedChats",JSONArray())
                        .put("notificationsWhileLocked",false).also { mutate?.invoke(it) }
                }
                GatewayEndpoints.STATUS -> {
                    statusReads.incrementAndGet()
                    val key = exchange.requestHeaders.getFirst("X-Pair-Key")
                    if (key !in setOf("A".repeat(43),newKey) || denyStatus) status=401
                    JSONObject().put("status","ONLINE").put("serverName","Synthetic authenticated PC").put("protocolVersion",1).put("scopes",scopes())
                }
                else -> { chatRequests.incrementAndGet();status=503;JSONObject().put("error",JSONObject().put("code","UNAVAILABLE")) }
            }
            val bytes = output.toString().toByteArray();exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }
    @Before fun setup() {
        startServer()
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cipher=object:VaultCipher {
            override fun encrypt(plaintext:String):String { beforeSave?.invoke();return VaultCodec.encrypt(plaintext,key) }
            override fun decrypt(encryptedPayload:String):String { beforeDecrypt?.invoke();return VaultCodec.decrypt(encryptedPayload,key) }
        }
        previous=DesktopNodeConfig(hostIp="127.0.0.1",port=server.address.port,pairKey="A".repeat(43),
            friendlyName="Existing synthetic pairing",batteryMode=BatteryOptimizationMode.MANUAL_ONLY,secureClinicalMode=true,
            certificateDer=Base64.encodeToString(certificate.encoded,Base64.NO_WRAP),certificateFingerprint=fingerprint(certificate))
        storage=SecureConfigStore(context,cipher);storage.clear();storage.save(previous)
        database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        repository=PrivateAIRepository(database,configStore=storage,cipher=cipher)
    }
    @After fun cleanup() { beforeSave=null;beforeDecrypt=null;repository.close();database.close();server.stop(0);executor.shutdownNow();storage.clear() }
    private suspend fun open() { repository.onUnlocked().join();assertTrue(repository.vaultReady.value) }

    @Test fun activeRoomCollectorResubscribesWhenPairingAdvancesWorkspaceGeneration() = runBlocking {
        open()
        val observed = kotlinx.coroutines.channels.Channel<List<String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { repository.chatSessions.collect { rows -> observed.send(rows.map { it.id }) } }
        try {
            withTimeout(10_000) { observed.receive() }
            repository.pairSecure(link())
            val id = repository.createNewSession("SYNTHETIC after pairing")
            withTimeout(10_000) { while (id !in observed.receive()) { } }
        } finally { collector.cancelAndJoin(); observed.close() }
    }
    private fun unchanged() { assertEquals(previous,repository.nodeConfig.value);assertEquals(previous,storage.load()) }
    private class ControlledDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        @Volatile var onDispatch: (() -> Unit)? = null
        override fun dispatch(context: CoroutineContext, block: Runnable) { onDispatch?.invoke();tasks.add(block) }
        fun runNext() { val task=tasks.poll(10,TimeUnit.SECONDS);assertNotNull("Expected a controlled coroutine continuation",task);task!!.run() }
        fun finish(job: Job) {
            // Room child cleanup may complete on its own dispatcher without dispatching
            // another controlled continuation. Wake for either event, without timing polls.
            val completed = Runnable {}
            val registration = job.invokeOnCompletion { tasks.add(completed) }
            try { repeat(16) { if(job.isCompleted)return;runNext() };assertTrue("Controlled opening must finish",job.isCompleted) }
            finally { registration.dispose();tasks.remove(completed) }
        }
        fun runPending() { repeat(16) { val task=tasks.poll() ?: return;task.run() } }
    }
    private suspend fun failure(code: String, operation: suspend () -> Unit) {
        try { operation();fail("Pairing must fail: $code") } catch (e: GatewayException) {
            assertEquals(code,e.code);assertFalse(e.message.orEmpty().contains(token));assertFalse(e.message.orEmpty().contains("privateai://"))
        }
    }
    @Test fun strictBootstrapParserRejectsAmbiguityPublicOriginsAndExpiredSecrets() {
        val valid=link();val parsed=PairingBootstrapParser.parse(valid)
        assertEquals(inviteId,parsed.id);assertFalse(parsed.toString().contains(token))
        val invalid=listOf(valid+"&token=$token",valid+"&unknown=1",valid+"#fragment",valid.replace("version=2","version=3"),
            valid.replace("host=127.0.0.1","host=8.8.8.8"),valid.replace("privateai://pair/v2","https://pair/v2"),
            valid.replace("privateai://pair/v2","privateai://user@pair/v2"),valid.replace("privateai://pair/v2","privateai://pair:443/v2"),
            valid.replace("port=${server.address.port}","port=65536"),valid.replace("&token=","&%74oken=")+"&token=$token",valid+"x".repeat(2048),
            valid.replace(token,"too-short"),link(expires=System.currentTimeMillis()-1),link(expires=System.currentTimeMillis()+600_000))
        for(value in invalid)try { PairingBootstrapParser.parse(value);fail("Malformed code must fail") } catch(e:GatewayException) { assertFalse(e.message.orEmpty().contains(token)) }
    }
    @Test fun legacyCertificatePairingRemainsCompatible() {
        val query=mapOf("host" to previous.hostIp,"port" to previous.port.toString(),"scheme" to "https","key" to previous.pairKey,
            "fingerprint" to previous.certificateFingerprint,"certificate" to previous.certificateDer).entries.joinToString("&") { it.key+"="+URLEncoder.encode(it.value,"UTF-8") }
        assertEquals(previous.copy(backgroundNotificationsEnabled=false),PairingConfigParser.parse("privateai://pair?$query",previous))
        assertFalse(PairingBootstrapParser.isBootstrap("privateai://pair?$query"))
    }
    @Test fun verifiedPairCommitsOnlyAfterAuthenticatedStatusAndKeepsOutboxAndPrivacyPreferences()=runBlocking {
        open()
        val queued=EncryptedChatMessage(UUID.randomUUID().toString(),"synthetic-session","user",cipher.encrypt("Synthetic pending source"),status="QUEUED_OFFLINE",desktopFingerprint=PairingConfigParser.destinationIdentity(previous))
        database.chatDao().insertMessage(queued)
        val reads=statusReads.get();val receipt=repository.pairSecure(link())
        assertEquals("Synthetic authenticated PC",receipt.serverName);assertEquals(setOf("chat","workflow"),receipt.approvedScopes);assertTrue(receipt.bootstrap)
        assertEquals(ConnectionStatus.CONNECTED,repository.connectionStatus.value);assertEquals(reads+1,statusReads.get());assertEquals(1,exchanges.get())
        val saved=repository.nodeConfig.value;assertEquals(newKey,saved.pairKey);assertEquals(previous.batteryMode,saved.batteryMode);assertEquals(previous.secureClinicalMode,saved.secureClinicalMode)
        assertEquals(saved,storage.load());assertEquals(listOf(queued),database.chatDao().getQueuedOfflineMessages());assertEquals(0,chatRequests.get())
        assertNotEquals(PairingConfigParser.destinationIdentity(previous),PairingConfigParser.destinationIdentity(saved))
        assertFalse(receipt.toString().contains(newKey));assertFalse(receipt.toString().contains(token))
    }
    @Test fun authorizedStatusOnlyPairingDoesNotAcquireChatOrWorkflowScopes()=runBlocking {
        open();statusOnly=true;val receipt=repository.pairSecure(link())
        assertEquals(emptySet<String>(),receipt.approvedScopes);assertEquals(ConnectionStatus.CONNECTED,repository.connectionStatus.value)
        assertEquals(emptySet<String>(),repository.nodeStats.value.scopes)
    }
    @Test fun acceptedExchangeResponseMayArriveAfterQrExpiryWithoutLosingItsValidDeviceKey()=runBlocking {
        val clock=java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val parsed=PairingBootstrapParser.parse(link(expires=clock.get()+180_000),clock.get())
        beforeExchange={clock.addAndGet(200_000)}
        val result=PairingBootstrapService(clock::get).exchange(parsed,previous) {}
        assertEquals(newKey,result.config.pairKey);assertEquals(1,exchanges.get())
    }
    @Test fun invalidFingerprintStopsTlsBeforeAnyHttpTokenBytesReachServer()=runBlocking {
        open();failure("TLS") { repository.pairSecure(link(pin="0".repeat(64))) };assertEquals(0,exchanges.get());unchanged()
    }
    @Test fun matchingFingerprintWithWrongIpSanStillStopsBeforeHttp()=runBlocking {
        open();server.stop(0);startServer("127.0.0.2")
        failure("TLS") { repository.pairSecure(link(host="127.0.0.2")) };assertEquals(0,exchanges.get());unchanged()
    }
    @Test fun redirectedExchangeIsNeverFollowedOrPersisted()=runBlocking {
        open();redirect=true;failure("PAIR_REDIRECT") { repository.pairSecure(link()) };assertEquals(1,exchanges.get());unchanged()
    }
    @Test fun mismatchedReceiptCannotReplacePreviousPairing()=runBlocking {
        open();mutate={it.put("baseUrl","https://192.168.1.2:8443")};failure("PAIR_PROTOCOL") { repository.pairSecure(link()) };unchanged()
    }
    @Test fun lostExchangeResponseIsNotRetriedAndOldConfigurationIsRetained()=runBlocking {
        open();dropResponse=true;val code=link();failure("PAIR_INTERRUPTED") { repository.pairSecure(code) };unchanged()
        failure("PAIR_ATTEMPTED") { repository.pairSecure(code) };assertEquals(1,exchanges.get());unchanged()
    }
    @Test fun failedAuthenticatedStatusCannotSaveAnExchangedKey()=runBlocking {
        open();denyStatus=true
        try { repository.pairSecure(link());fail("Authenticated status is required") } catch (_:GatewayException) {}
        assertEquals(1,exchanges.get());unchanged()
    }
    @Test fun cancellationAfterExchangePreventsStatusAndConfigurationSave()=runBlocking {
        open();val reads=statusReads.get();beforeExchange={repository.cancelPairing()}
        try { repository.pairSecure(link());fail("Cancelled exchange cannot save") } catch (_:CancellationException) {}
        assertEquals(reads,statusReads.get());unchanged()
    }
    @Test fun lockDuringExchangeCannotReplaceOldPairing()=runBlocking {
        open();beforeExchange={repository.onLocked()}
        try { repository.pairSecure(link());fail("Old unlock epoch cannot save") } catch (_:CancellationException) {}
        assertFalse(repository.vaultReady.value);unchanged()
    }
    @Test fun lockPublishesClosedStateBeforePairingCancellationDispatch()=runBlocking {
        repository.close()
        val dispatcher=ControlledDispatcher()
        repository=PrivateAIRepository(database,configStore=storage,cipher=cipher,dispatcher=dispatcher)
        val opened=repository.onUnlocked();dispatcher.finish(opened);assertTrue(repository.vaultReady.value)
        val reached=CountDownLatch(1);val release=CountDownLatch(1);val cancelled=CountDownLatch(1)
        val observed=CopyOnWriteArrayList<Boolean>()
        beforeExchange={reached.countDown();assertTrue("Release the synthetic response",release.await(10,TimeUnit.SECONDS))}
        val attempt=async(start=CoroutineStart.UNDISPATCHED) { repository.pairSecure(link()) }
        try {
            dispatcher.runNext();assertTrue(reached.await(10,TimeUnit.SECONDS))
            dispatcher.onDispatch={observed.add(repository.vaultReady.value);cancelled.countDown()}
            repository.onLocked()
            assertTrue("Cancellation must dispatch its suspended continuation",cancelled.await(10,TimeUnit.SECONDS))
            assertTrue(observed.isNotEmpty());assertFalse("Every cancellation observer must see a closed vault",observed.any { it })
            dispatcher.onDispatch=null;release.countDown();dispatcher.runNext()
            try { attempt.await();fail("Revoked pairing must be cancelled") } catch (_:CancellationException) {}
            assertFalse(repository.vaultReady.value);unchanged()
        } finally { dispatcher.onDispatch=null;release.countDown();attempt.cancel();dispatcher.runPending() }
    }
    @Test fun queuedAndReentrantUnlockCannotReopenARevokedWorkspace() {
        repository.close()
        val dispatcher=ControlledDispatcher()
        repository=PrivateAIRepository(database,configStore=storage,cipher=cipher,dispatcher=dispatcher)
        val queued=repository.onUnlocked();repository.onLocked();dispatcher.finish(queued)
        assertFalse(repository.vaultReady.value)
        beforeDecrypt={beforeDecrypt=null;repository.onLocked()}
        val reentrant=repository.onUnlocked();dispatcher.finish(reentrant)
        assertFalse(repository.vaultReady.value)
        val valid=repository.onUnlocked();dispatcher.finish(valid)
        assertTrue(repository.vaultReady.value);unchanged()
    }
    @Test fun changedConfigurationDuringExchangeCannotBeOverwrittenByOldPairingAttempt()=runBlocking {
        open();val settingsJob=CompletableDeferred<Job>()
        val changed=previous.copy(friendlyName="Synthetic concurrent settings")
        beforeExchange={settingsJob.complete(repository.updateConfig(changed))}
        try { repository.pairSecure(link());fail("Old config epoch cannot commit") } catch (_:CancellationException) {}
        withTimeout(10_000) { settingsJob.await().join() }
        assertEquals(changed,repository.nodeConfig.value);assertEquals(changed,storage.load())
        assertEquals(previous.pairKey,repository.nodeConfig.value.pairKey)
    }
    @Test fun guardedAtomicFileWriteFailureRestoresOriginalEncryptedConfiguration() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val file=java.io.File(context.noBackupFilesDir,"private-ai-gateway-v2.bin")
        val bytes=file.readBytes();var checks=0
        try { storage.save(previous.copy(pairKey=newKey),beforeCommit={if(++checks==2)throw IllegalStateException("Synthetic revoked write")});fail("Guard must abort commit") }
        catch (_:IllegalStateException) {}
        assertEquals(2,checks);assertArrayEquals(bytes,file.readBytes());assertEquals(previous,storage.load())
    }
    @Test fun encryptedSaveFailurePreservesOldAtomicConfigurationAndPreferences()=runBlocking {
        open();beforeSave={throw IllegalStateException("Synthetic encryption failure")}
        failure("PAIR_FAILED") { repository.pairSecure(link()) };beforeSave=null;unchanged()
    }
    @Test fun cancellationDuringEncryptionIsRecheckedBeforeAtomicCommit()=runBlocking {
        open();beforeSave={repository.cancelPairing()}
        try { repository.pairSecure(link());fail("Cancelled save cannot commit") } catch (_:CancellationException) {}
        beforeSave=null;unchanged()
    }
}
