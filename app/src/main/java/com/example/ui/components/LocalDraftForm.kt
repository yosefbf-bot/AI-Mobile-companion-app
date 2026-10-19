package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatAttachment
import com.example.data.repository.LocalDraftToken
import com.example.ui.LocalDraftController

class LocalDraftFormState internal constructor(
    val form: String, val reset: Long,
    private val change: (String, ChatAttachment?) -> Unit,
    internal val register: (() -> Pair<String, ChatAttachment?>) -> (() -> Unit)
) {
    fun update(fields: String, evidence: ChatAttachment? = null) = change(fields, evidence)
}

/** Restoring must finish before the child can emit an initial empty autosave. */
@Composable
fun LocalDraftForm(controller: LocalDraftController, route: String, compact: Boolean = false,
                   content: @Composable (LocalDraftFormState) -> Unit) {
    val observed = remember(controller, route) { controller.state(route) }
    val state by observed.collectAsStateWithLifecycle()
    val autoSave by controller.autoSave.collectAsStateWithLifecycle()
    DisposableEffect(controller, route, observed) { onDispose { controller.leave(route, observed) } }
    var deleting by remember(route) { mutableStateOf<LocalDraftToken?>(null) }
    if (state.loading) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("draft_loading")) {
            Text(state.error ?: "Restoring this phone's draft…")
            if (state.error != null) TextButton(onClick = { controller.retry(route) }) { Text("Retry draft") }
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else {
        Column(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            if (state.hasContent || state.hasSavedDraft || state.error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.error != null) state.error!! else if (!autoSave) "Auto-save off · previous saved draft kept"
                    else if (state.persisted) "Draft saved on this phone" else "Saving phone draft…",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("draft_status"))
                if (state.error != null) TextButton(onClick = { controller.retry(route) }) { Text("Retry") }
                TextButton(onClick = { deleting = state.token }, modifier = Modifier.testTag("delete_local_draft")) { Text("Delete draft") }
            }
            Box(if (compact) Modifier.fillMaxWidth() else Modifier.weight(1f)) {
                key(route, state.reset) {
                    // An old form handle must retain its own reset epoch, rather than
                    // reading a replacement form's reset through the delegated state.
                    val observedReset = state.reset
                    content(LocalDraftFormState(state.form, observedReset,
                        { fields, evidence -> controller.update(route, observedReset, fields, evidence) },
                        { provider -> controller.registerCapture(route, observedReset, provider) }))
                }
            }
        }
    }
    deleting?.let { token ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this phone draft?") },
            text = { Text("This removes only this unfinished local draft. Saved records, queued work and desktop messages stay saved.") },
            confirmButton = { TextButton(onClick = { deleting = null; controller.delete(route, token) },
                modifier = Modifier.testTag("confirm_delete_local_draft")) { Text("Delete draft") } },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.testTag("cancel_delete_local_draft")) { Text("Cancel") } })
    }
}

/** Read displayed state synchronously before pause/lock, without rebuilding evidence JSON. */
@Composable
fun CaptureLatestDraft(state: LocalDraftFormState?, value: () -> Pair<String, ChatAttachment?>) {
    val current by rememberUpdatedState(value)
    DisposableEffect(state?.reset) {
        val remove = state?.register { current() }
        onDispose { remove?.invoke() }
    }
}

@Composable
fun LocalDraftDialog(controller: LocalDraftController, route: String, title: String,
    onDismiss: () -> Unit, content: @Composable (LocalDraftFormState) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { LocalDraftForm(controller, route, compact = true, content = content) },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
