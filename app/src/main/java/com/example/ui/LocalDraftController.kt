package com.example.ui

import com.example.data.crypto.EncryptionManager
import com.example.data.model.ChatAttachment
import com.example.data.repository.LocalDraftToken
import com.example.data.repository.LocalDraftSubmission
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class LocalDraftUiState(
    val loading: Boolean = true, val form: String = "", val token: LocalDraftToken? = null,
    val persisted: Boolean = false, val error: String? = null, val reset: Long = 0L,
    val hasContent: Boolean = false, val hasSavedDraft: Boolean = false
)

/** UI adapter only. The repository owns encrypted RAM, bounded writes, tombstones and migration. */
class LocalDraftController(
    private val repository: PrivateAIRepository, private val scope: CoroutineScope,
    private val preferences: AutoDraftPreferences,
    private val ready: () -> Boolean, private val scopeIdentity: () -> String
) {
    private val states = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<LocalDraftUiState>>()
    private val routes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private data class PreparedContext(val scope: String, val generation: Long)
    private val submissions = java.util.Collections.synchronizedMap(java.util.WeakHashMap<LocalDraftSubmission, PreparedContext>())
    private class Capture(val reset: Long, val value: () -> Pair<String, ChatAttachment?>)
    private val captures = mutableMapOf<String, Capture>()
    fun registerCapture(route: String, reset: Long, value: () -> Pair<String, ChatAttachment?>): () -> Unit {
        val key = key(route); val capture = Capture(reset, value); captures[key] = capture
        return {
            if (captures[key] === capture) {
                if (ready() && key == key(route)) {
                    val (fields, evidence) = capture.value()
                    update(route, capture.reset, fields, evidence)
                }
                captures.remove(key)
            }
        }
    }
    fun captureBeforeLock(): Boolean {
        if (!ready()) return false
        val generation = EncryptionManager.accessGeneration
        var captured = true
        captures.toMap().forEach { (cachedKey, capture) ->
            val route = routes[cachedKey] ?: return@forEach
            if (cachedKey == key(route)) {
                val (fields, evidence) = capture.value()
                update(route, capture.reset, fields, evidence)
                val state = states[cachedKey]
                if (state == null || !isCurrent(state, generation) || state.value.error != null) captured = false
            }
        }
        return captured && ready() && EncryptionManager.accessGeneration == generation
    }
    private val enabled = MutableStateFlow(preferences.readEnabled())
    val autoSave: StateFlow<Boolean> = enabled.asStateFlow()
    init {
        scope.launch {
            repository.localDraftWriteVersion.collect {
                if (ready()) states.forEach { (cachedKey, state) ->
                    val route = routes[cachedKey] ?: return@forEach
                    if (cachedKey == key(route)) refreshStatus(state)
                }
            }
        }
    }
    private fun key(route: String) = scopeIdentity() + ":" + route
    private fun isCurrent(state: MutableStateFlow<LocalDraftUiState>, generation: Long): Boolean =
        ready() && EncryptionManager.accessGeneration == generation && states.values.any { it === state }

    fun state(route: String): StateFlow<LocalDraftUiState> {
        val key = key(route)
        val state = states.getOrPut(key) { MutableStateFlow(LocalDraftUiState()) }
        routes[key] = route
        state.value = state.value.copy(loading = true, form = "")
        scope.launch { load(route, key, state) }
        return state
    }
    private suspend fun load(route: String, key: String, state: MutableStateFlow<LocalDraftUiState>) {
        val generation = EncryptionManager.accessGeneration
        try {
            if (!ready()) return
            val snapshot = repository.loadLocalDraft(route)
            if (key == key(route) && isCurrent(state, generation))
                state.value = LocalDraftUiState(false, snapshot.form, snapshot.token, snapshot.persisted, snapshot.error,
                    state.value.reset + 1, hasLocalDraftWork(snapshot.form), snapshot.hasSavedDraft)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (key == key(route) && isCurrent(state, generation)) state.value = state.value.copy(
                loading = true, error = "Could not restore this phone draft. Retry before editing.")
        }
    }
    fun retry(route: String) {
        val key = key(route); val state = states[key] ?: return
        scope.launch {
            if (state.value.loading) load(route, key, state)
            else { captureBeforeLock(); flush() }
        }
    }
    fun leave(route: String, observed: StateFlow<LocalDraftUiState>) {
        val cachedKey = key(route)
        states[cachedKey]?.takeIf { it === observed }?.let { state ->
            // Either parent or child may dispose first. Both capture before releasing
            // the provider; an old provider cannot overwrite a replacement registration.
            captures[cachedKey]?.let { capture ->
                if (ready()) {
                    val (fields, evidence) = capture.value()
                    update(route, capture.reset, fields, evidence)
                }
            }
            state.value = state.value.copy(loading = true, form = "")
        }
    }
    fun onLocked() {
        // No plaintext draft remains in a retained StateFlow while locked. Core keeps encrypted work.
        states.values.forEach { it.value = LocalDraftUiState(reset = it.value.reset + 1) }
        captures.clear(); states.clear(); routes.clear(); submissions.clear()
    }
    fun setAutoSave(value: Boolean) {
        if (!ready()) return
        try {
            preferences.saveEnabled(value); enabled.value = value
            if (value) {
                captureBeforeLock()
                // Explicitly enabling autosave admits previously RAM-only edits too.
                // Full evidence hydration occurs once here, never per keystroke.
                states.forEach { (cachedKey, state) ->
                    val route = routes[cachedKey] ?: return@forEach
                    if (cachedKey == key(route)) synchronized(state) {
                        val generation = EncryptionManager.accessGeneration
                        val before = state.value
                        before.token?.let { token ->
                            val current = repository.currentLocalDraft(token)
                            if (hasLocalDraftWork(current.form)) {
                                val next = repository.updateLocalDraft(token, current.form, true)
                                if (isCurrent(state, generation)) state.value = before.copy(token = next, persisted = false)
                            }
                        }
                    }
                }
            }
        }
        catch (_: Exception) { states.values.forEach { it.value = it.value.copy(error = "Draft preference could not be saved.") } }
    }
    fun update(route: String, reset: Long, fields: String, evidence: ChatAttachment? = null) {
        val state = states[key(route)] ?: return
        synchronized(state) {
        val generation = EncryptionManager.accessGeneration
        val before = state.value
        if (!ready() || before.loading || before.reset != reset) return
        val hasContent = evidence != null || hasLocalDraftWork(fields)
        if (!hasContent && !before.hasContent) return
        try {
            val token = repository.updateLocalDraftFields(before.token ?: return, fields, evidence, enabled.value)
            if (isCurrent(state, generation)) state.value = before.copy(token = token, persisted = false, error = null, hasContent = hasContent)
        } catch (_: Exception) {
            // The core retains the previous accepted encrypted RAM value on failure.
            if (isCurrent(state, generation)) state.value = before.copy(error = "Draft not saved. Keep this app open and retry before leaving.")
        }
        }
    }
    fun updateSerialized(route: String, form: String) {
        val state = states[key(route)] ?: return
        synchronized(state) {
        val generation = EncryptionManager.accessGeneration
        val before = state.value
        if (!ready() || before.loading) return
        try {
            val token = repository.updateLocalDraft(before.token ?: return, form, enabled.value)
            if (isCurrent(state, generation)) state.value = before.copy(token = token, persisted = false, error = null)
        } catch (_: Exception) { if (isCurrent(state, generation)) state.value = before.copy(error = "Draft not saved. Keep this app open and retry.") }
        }
    }
    fun currentForm(route: String): String = try {
        if (!ready()) "" else states[key(route)]?.value?.token?.let(repository::currentLocalDraft)?.form.orEmpty()
    } catch (_: Exception) { "" }
    fun prepareSubmission(route: String, submitted: String, fields: Set<String>): LocalDraftSubmission? {
        if (submitted.isBlank()) return null
        val state = states[key(route)] ?: return null
        synchronized(state) {
            if (!ready() || state.value.loading) return null
            val generation = EncryptionManager.accessGeneration
            val submission = repository.prepareLocalDraftSubmission(state.value.token ?: return null, submitted, fields)
            check(isCurrent(state, generation)) { "The draft workspace changed before submission." }
            submissions[submission] = PreparedContext(key(route), generation)
            return submission
        }
    }
    fun acknowledge(route: String, submitted: String, reconcile: (String, String) -> String) =
        acknowledge(route, submitted, null, reconcile)
    fun acknowledge(route: String, submitted: String, submission: LocalDraftSubmission?, reconcile: (String, String) -> String) {
        val context = submission?.let { submissions.remove(it) ?: return }
        val state = states[key(route)] ?: return
        if (context != null && (context.scope != key(route) || !isCurrent(state, context.generation))) return
        synchronized(state) {
        val generation = EncryptionManager.accessGeneration
        val before = state.value
        try {
            val token = repository.acknowledgeLocalDraft(before.token ?: return, submitted, submission, reconcile)
            if (isCurrent(state, generation)) state.value = before.copy(token = token)
            refreshStatus(state)
        } catch (_: Exception) { if (isCurrent(state, generation)) state.value = before.copy(error = "Saved work is safe; its local draft could not be updated. Retry the draft.") }
        }
    }
    private fun refreshStatus(state: MutableStateFlow<LocalDraftUiState>) {
        synchronized(state) {
        val generation = EncryptionManager.accessGeneration
        val before = state.value
        if (!ready() || before.loading) return
        try {
            val status = repository.localDraftStatus(before.token ?: return)
            if (isCurrent(state, generation)) state.value = before.copy(persisted = status.persisted, error = status.error, hasSavedDraft = status.hasSavedDraft)
        } catch (_: Exception) { /* A newer token or lock owns this state. */ }
        }
    }
    fun delete(route: String, expected: LocalDraftToken) {
        val state = states[key(route)] ?: return
        if (!ready() || state.value.token != expected) return
        val generation = EncryptionManager.accessGeneration
        state.value = state.value.copy(loading = true, form = "")
        scope.launch {
            try {
                val snapshot = repository.deleteLocalDraft(expected)
                if (isCurrent(state, generation) && state.value.token == expected) state.value = LocalDraftUiState(
                    false, snapshot.form, snapshot.token, snapshot.persisted, snapshot.error, state.value.reset + 1,
                    hasLocalDraftWork(snapshot.form), snapshot.hasSavedDraft)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (isCurrent(state, generation)) state.value = state.value.copy(error = "Draft changed or could not be deleted. Retry before editing.") }
        }
    }
    suspend fun flush(): Boolean {
        val generation = EncryptionManager.accessGeneration
        val identity = scopeIdentity()
        return try {
        repository.flushLocalDrafts()
        if (ready() && EncryptionManager.accessGeneration == generation && scopeIdentity() == identity) states.values.forEach(::refreshStatus)
        true
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) {
        if (ready() && EncryptionManager.accessGeneration == generation && scopeIdentity() == identity) states.values.forEach { it.value = it.value.copy(error = "Draft not saved. Keep this app open and retry.") }
        false
        }
    }
}

/** Defaults are controls, not unfinished work. Evidence content is never copied here. */
internal fun hasLocalDraftWork(form: String): Boolean = try {
    val data = org.json.JSONObject(form)
    data.keys().asSequence().any { key ->
        if (key in setOf("kind", "tags", "type")) false else when (val value = data.opt(key)) {
            null, org.json.JSONObject.NULL -> false
            is Boolean -> value
            is String -> value.isNotBlank()
            else -> true
        }
    }
} catch (_: Exception) { false }
