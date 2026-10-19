package com.example.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.example.data.local.AppDatabase
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface VaultCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encryptedPayload: String): String
}

class VaultUnavailableException(cause: Throwable? = null) :
    IllegalStateException("Vault unavailable. Unlock with the device screen lock or biometrics.", cause)

/** Keys remain nonexportable. Opt-in duration changes preserve the original key/data. */
object EncryptionManager : VaultCipher {
    private const val AUTH_SECONDS = 300
    @Volatile private var applicationContext: Context? = null
    private val access = MutableStateFlow(false)
    private val committedMinutes = MutableStateFlow(5)
    private val policyError = MutableStateFlow<String?>(null)
    val sessionPolicyChangeError: StateFlow<String?> = policyError.asStateFlow()
    val committedSessionMinutes: StateFlow<Int> = committedMinutes.asStateFlow()
    private val generation = AtomicLong()
    private val session = ForegroundSessionLease(SystemClock::elapsedRealtime)
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val migration = Mutex()
    private var expiry: Job? = null
    private var policy = VaultPolicyState()
    private var authenticatedAt: Long? = null
    private var originalCreationProof: Long? = null
    val isUnlocked: StateFlow<Boolean> = access.asStateFlow()
    val accessGeneration: Long get() = synchronized(this) { hasCurrentAccess(); generation.get() }

    // Only used at fresh OS authentication or within the checked migration epoch.
    private val journalCipher = object : VaultCipher {
        override fun encrypt(plaintext: String) = VaultCodec.encrypt(plaintext, key(0))
        override fun decrypt(encryptedPayload: String) = VaultCodec.decrypt(encryptedPayload, key(0))
    }
    private fun policyStore() = VaultPolicyStore(applicationContext ?: throw VaultUnavailableException(), journalCipher)
    fun initialize(context: Context) { applicationContext = context.applicationContext }

    @Synchronized fun lock() {
        expiry?.cancel(); expiry = null
        session.revoke(); authenticatedAt = null; originalCreationProof = null
        generation.incrementAndGet(); access.value = false
    }

    @Synchronized fun hasCurrentAccess(): Boolean {
        if (access.value && (session.remainingMillis() == 0L || deviceLocked())) lock()
        return access.value
    }
    private fun deviceLocked(): Boolean = DeviceScreenLock.read(applicationContext) != DeviceScreenLockState.SECURE_UNLOCKED
    @Synchronized fun remainingSessionMillis(): Long = if (hasCurrentAccess()) session.remainingMillis() else 0
    @Synchronized fun hasPendingPolicyMigration(): Boolean = hasCurrentAccess() && policy.target != null

    /** IO-only inventory permits empty or genuinely retired-only data, never lost protected keys. */
    internal suspend fun prepareOriginalKeyCreation() = withContext(Dispatchers.IO) {
        val epoch = accessGeneration
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(PolicyVaultCodec.alias(0))) return@withContext
        val context = applicationContext ?: throw VaultUnavailableException()
        if (hasPolicyAliases() || policyStore().exists() || SecureConfigStore(context).hasProtectedFiles() ||
            VaultStorageInventory.hasProtectedRows(AppDatabase.getInstance(context)) ||
            VaultStorageInventory.hasProtectedRows(AppDatabase.getDemoInstance(context))) throw VaultUnavailableException()
        ensureActive()
        synchronized(this@EncryptionManager) {
            if (generation.get() != epoch || access.value || deviceLocked()) throw VaultUnavailableException()
            originalCreationProof = epoch
        }
    }

    @Synchronized internal fun discardOriginalKeyPreparation(epoch: Long) {
        if (originalCreationProof == epoch) originalCreationProof = null
    }
    /** Await revoked old migration cleanup without blocking the Android main thread. */
    internal suspend fun awaitPolicyIdle() { migration.withLock { } }

    /** Optional request comes only from a confirmed setting followed by new real OS auth. */
    @Synchronized internal fun unlockAfterDeviceAuthentication(authenticatedAtElapsed: Long = SystemClock.elapsedRealtime(),
        requestedSessionMinutes: Int? = null) {
        val mayCreateOriginal = originalCreationProof == generation.get()
        lock()
        try {
            requestedSessionMinutes?.let { require(it in PolicyVaultCodec.MINUTES) }
            val now = SystemClock.elapsedRealtime()
            require(authenticatedAtElapsed >= 0 && now >= authenticatedAtElapsed &&
                now - authenticatedAtElapsed < AUTH_SECONDS * 1000L)
            if (deviceLocked()) throw VaultUnavailableException()
            val requestedKey = requestedSessionMinutes?.let { if (it == 5) 0 else it }
            val store = policyStore()
            // Missing/corrupt policy after opt-in cannot silently become an empty/default vault.
            if (!store.exists() && hasPolicyAliases()) throw VaultUnavailableException()
            val original = key(0, create = mayCreateOriginal && !store.exists())
            VaultCodec.encrypt("device authentication check", original)
            var state = store.read()
            if (state.target != null && requestedKey != null && requestedKey != state.target)
                throw VaultUnavailableException()
            if (state.target == null && requestedKey != null && requestedKey != state.current) {
                // Journal precedes target creation: crash recovery can distinguish a not-yet-created
                // key from a missing key that has already encrypted data.
                state = VaultPolicyState(state.current, requestedKey, "KEY_REQUESTED")
                store.write(state)
            }
            val prepared = VaultPolicyPreparation.finish(store, state) { target, create ->
                PolicyVaultCodec.encrypt("target authentication check", key(target, create), target)
            }
            state = prepared.state
            policyError.value = if (prepared.rejected)
                "The session setting could not be changed. Your previous limit and saved data were kept." else null
            PolicyVaultCodec.encrypt("current authentication check", key(state.current), state.current)
            policy = state
            committedMinutes.value = PolicyVaultCodec.minutes(state.current)
            authenticatedAt = authenticatedAtElapsed
            val initialMinutes = minOf(PolicyVaultCodec.minutes(state.current), state.target?.let(PolicyVaultCodec::minutes) ?: Int.MAX_VALUE)
            session.grant(authenticatedAtElapsed, initialMinutes)
            if (session.remainingMillis() == 0L) throw VaultUnavailableException()
            val grantedGeneration = generation.incrementAndGet()
            access.value = true
            expiry = expiryScope.launch {
                while (true) {
                    val remaining = synchronized(this@EncryptionManager) {
                        if (generation.get() != grantedGeneration || !hasCurrentAccess()) return@launch
                        session.remainingMillis()
                    }
                    delay(remaining.coerceAtLeast(1L))
                }
            }
        } catch (failure: Exception) { lock(); throw VaultUnavailableException(failure) }
    }

    /** Called while repository ready=false, after both legacy migrations and old work has joined. */
    suspend fun completePendingPolicyMigration(real: AppDatabase, demo: AppDatabase?, config: SecureConfigStore?,
        rewrapMemory: suspend ((String) -> String, () -> Unit) -> Unit = { _, _ -> }) = migration.withLock {
        currentCoroutineContext().ensureActive()
        val epoch = accessGeneration
        val target = synchronized(this) { if (!hasCurrentAccess()) throw VaultUnavailableException(); policy.target } ?: return@withLock
        val demoStore = demo ?: throw VaultUnavailableException()
        val configuration = config ?: throw VaultUnavailableException()
        fun checkEpoch() { synchronized(this) { if (!hasCurrentAccess() || generation.get() != epoch) throw VaultUnavailableException() } }
        fun rewrite(value: String): String = useKey {
            val sourceId = PolicyVaultCodec.keyId(value)
            val plaintext = PolicyVaultCodec.decrypt(value, key(sourceId))
            if (sourceId == target) value else PolicyVaultCodec.encrypt(plaintext, key(target), target).also {
                check(PolicyVaultCodec.decrypt(it, key(target)) == plaintext)
            }
        }
        fun phase(name: String) { checkEpoch(); val next = synchronized(this) { policy.copy(phase = name) }
            policyStore().write(next, ::checkEpoch); synchronized(this) { checkEpoch(); policy = next } }
        try {
            withContext(Dispatchers.IO) {
                checkEpoch()
                VaultPolicyMigration.convert(real, ::rewrite, ::checkEpoch); phase("REAL_DB_DONE")
                VaultPolicyMigration.convert(demoStore, ::rewrite, ::checkEpoch); phase("DEMO_DB_DONE")
                configuration.rewrapForPolicy(::rewrite, ::checkEpoch); phase("FILES_DONE")
                checkEpoch(); rewrapMemory(::rewrite, ::checkEpoch); checkEpoch()
                checkEpoch()
                val committed = VaultPolicyState(target)
                policyStore().write(committed, ::checkEpoch)
                synchronized(this@EncryptionManager) {
                    checkEpoch()
                    val originalAuthentication = authenticatedAt ?: throw VaultUnavailableException()
                    policy = committed
                    session.grant(originalAuthentication, PolicyVaultCodec.minutes(target))
                    if (!hasCurrentAccess()) throw VaultUnavailableException()
                    committedMinutes.value = PolicyVaultCodec.minutes(target)
                }
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException) revokeIfCurrent(epoch)
            throw failure
        }
    }

    private fun hasPolicyAliases(): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return PolicyVaultCodec.MINUTES.any { store.containsAlias(PolicyVaultCodec.alias(it)) }
    }
    private fun key(id: Int, create: Boolean = false): SecretKey {
        if (deviceLocked()) throw VaultUnavailableException()
        val alias = PolicyVaultCodec.alias(id)
        val seconds = if (id == 0) AUTH_SECONDS else PolicyVaultCodec.minutes(id) * 60
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!create) throw VaultUnavailableException()
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(seconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else { @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(seconds) }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
        }
        val result = store.getKey(alias, null) as? SecretKey ?: throw VaultUnavailableException()
        val info = SecretKeyFactory.getInstance(result.algorithm, "AndroidKeyStore").getKeySpec(result, KeyInfo::class.java) as KeyInfo
        check(info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds == seconds)
        return result
    }
    override fun encrypt(plaintext: String): String = useKey {
        val id = synchronized(this) { policy.current }; PolicyVaultCodec.encrypt(plaintext, key(id), id)
    }
    override fun decrypt(encryptedPayload: String): String = useKey {
        PolicyVaultCodec.decrypt(encryptedPayload, key(PolicyVaultCodec.keyId(encryptedPayload)))
    }
    private fun useKey(operation: () -> String): String {
        val epoch = synchronized(this) { if (!hasCurrentAccess()) throw VaultUnavailableException(); generation.get() }
        return try {
            val result = operation()
            synchronized(this) { if (!hasCurrentAccess() || generation.get() != epoch) throw VaultUnavailableException() }
            result
        } catch (failure: Exception) { revokeIfCurrent(epoch); throw VaultUnavailableException(failure) }
    }
    @Synchronized private fun revokeIfCurrent(epoch: Long) {
        if (generation.get() == epoch) lock()
    }
    fun computeSha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
