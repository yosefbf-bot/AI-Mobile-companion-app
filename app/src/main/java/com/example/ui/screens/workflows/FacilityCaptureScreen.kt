package com.example.ui.screens.workflows

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.crypto.AttachmentUtils
import com.example.data.model.*
import com.example.ui.components.*
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FacilityCaptureScreen(
    snapshot: WorkflowSnapshot, busy: Boolean, error: String?,
    onCapture: (String,String,String,String,Long?,String?,ChatAttachment?) -> Unit,
    onRefresh: () -> Unit, onUpdateAction: (WorkflowAction,String,Long?,String,Boolean) -> Unit,
    onSaveDraft: (WorkflowDraft,String,String,String) -> Unit, onAcknowledge: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier,
    onPickEvidence: ((String) -> Unit)? = null, onTakePhoto: (() -> Unit)? = null,
    incomingEvidence: ChatAttachment? = null, onConsumeEvidence: () -> Unit = {},
    loadUnsentDraft: () -> String = { "" }, onUnsentDraftChange: (String) -> Unit = {},
    lastSavedCaptureId: String? = null
) {
    val context = LocalContext.current
    val restored = remember { unsentForm(loadUnsentDraft()) }
    var note by remember { mutableStateOf(restored.optString("note")) }
    var kind by remember { mutableStateOf(restored.optString("kind", "round")) }
    var location by remember { mutableStateOf(restored.optString("location")) }
    var owner by remember { mutableStateOf(restored.optString("owner")) }
    var deadline by remember { mutableStateOf(restored.optString("deadline")) }
    var channel by remember { mutableStateOf(restored.optString("channel").takeIf(String::isNotBlank)) }
    var evidence by remember { mutableStateOf(formAttachment(restored)) }
    val form = remember(note,kind,location,owner,deadline,channel,evidence) {
        JSONObject().put("note",note).put("kind",kind).put("location",location).put("owner",owner)
            .put("deadline",deadline).put("channel",channel ?: "").put("evidence",attachmentForm(evidence)).toString()
    }
    var submittedForm by remember { mutableStateOf<Pair<String,String?>?>(null) }
    LaunchedEffect(lastSavedCaptureId) {
        submittedForm?.let { (savedForm, priorId) ->
            if (lastSavedCaptureId != null && lastSavedCaptureId != priorId) {
                if (form == savedForm) { note = ""; location = ""; owner = ""; deadline = ""; channel = null; evidence = null }
                submittedForm = null
            }
        }
    }
    val dictate = rememberOnDeviceDictation { note = if (note.isBlank()) it else "$note $it" }
    LaunchedEffect(form) { onUnsentDraftChange(form) }
    LaunchedEffect(incomingEvidence) { incomingEvidence?.let { evidence = it; onConsumeEvidence() } }
    var localError by remember { mutableStateOf<String?>(null) }
    var selectedAction by remember { mutableStateOf<WorkflowAction?>(null) }
    var selectedDraft by remember { mutableStateOf<WorkflowDraft?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            evidence = AttachmentUtils.uriToChatAttachment(context, uri)
            if (evidence == null) localError = "File could not be read or exceeds the evidence limit."
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) evidence = AttachmentUtils.bitmapToChatAttachment(bitmap)
    }
    LazyColumn(modifier.fillMaxSize().testTag("facility_capture_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                Button(onClick = onRefresh, enabled = !busy) { Text("Refresh desktop") }
            }
            Text("Rounds, notes and follow-ups", style = MaterialTheme.typography.headlineSmall)
            Text("The desktop saves review proposals in its Action Center. Missing owners and dates remain unknown. Photos are evidence for review; the text model does not interpret them.")
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Capture a note", style = MaterialTheme.typography.titleMedium)
                    Row { listOf("round","note","reminder").forEach { value ->
                        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(value.replaceFirstChar(Char::uppercase)) })
                        Spacer(Modifier.width(6.dp))
                    } }
                    OutlinedTextField(note, { note = it }, label = { Text("What did you observe or want to follow up?") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("capture_note"))
                    TextButton(onClick = dictate, enabled = !busy) { Text("Dictate on device") }
                    OutlinedTextField(location, { location = it }, label = { Text("Location, if known") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(owner, { owner = it }, label = { Text("Responsible person, if known") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(deadline, { deadline = it }, label = { Text("Due date/time: yyyy-MM-dd HH:mm (phone time zone)") }, modifier = Modifier.fillMaxWidth())
                    Text("Leave a date blank when it is unknown. No urgency or deadline is invented.")
                    Row {
                        FilterChip(channel == "outlook", { channel = if (channel == "outlook") null else "outlook" }, label = { Text("Outlook draft") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(channel == "whatsapp", { channel = if (channel == "whatsapp") null else "whatsapp" }, label = { Text("WhatsApp draft") })
                    }
                    Text("A draft is stored for review. Saving it does not send a message.")
                    Row {
                        OutlinedButton(onClick = { onPickEvidence?.invoke("*/*") ?: filePicker.launch("*/*") }, enabled = !busy) { Text("Attach evidence") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onTakePhoto?.invoke() ?: camera.launch(null) }, enabled = !busy) { Text("Camera") }
                    }
                    evidence?.let { file ->
                        Text("Evidence: ${file.name} — ${file.sizeBytes / 1024} KiB. JPEG, PNG, PDF or TXT, up to 5 MiB.")
                        TextButton(onClick = { evidence = null }) { Text("Remove evidence") }
                    }
                    Button(onClick = {
                        try {
                            val due = parseDeadline(deadline)
                            require(note.isNotBlank() && note.length <= 12000)
                            localError = null
                            submittedForm = form to lastSavedCaptureId
                            onCapture(note, kind, location, owner, due, channel, evidence)
                        } catch (_: Exception) { localError = "Enter a note and a valid due date/time, or leave the date blank." }
                    }, enabled = !busy && note.isNotBlank(), modifier = Modifier.testTag("capture_save")) { Text("Save for desktop review") }
                }
            }
        }
        item {
            Text("Desktop actions", style = MaterialTheme.typography.titleLarge)
            if (snapshot.actions.isEmpty()) Text("No action records are available. Check the connection and this phone's Workflow permission on the desktop.")
        }
        snapshot.actions.forEach { action -> item(key = "action-${action.id}") {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(action.title, style = MaterialTheme.typography.titleMedium)
                    Text(action.description)
                    Text("Status: ${action.status} · Owner: ${action.owner.ifBlank { "Unknown" }} · Due: ${formatDeadline(action.dueAt)}")
                    if (action.reviewRequired) Text("Review required; this is a proposed action, not a confirmed clinical finding.")
                    if (action.unknownFields.isNotEmpty()) Text("Unknown: ${action.unknownFields.joinToString(", ")}")
                    OutlinedButton(onClick = { selectedAction = action }, enabled = !busy) { Text("Review or update") }
                }
            }
        } }
        item { Text("Review drafts", style = MaterialTheme.typography.titleLarge) }
        snapshot.drafts.forEach { draft -> item(key = "draft-${draft.id}") {
            Card { Column(Modifier.padding(16.dp)) {
                Text("${draft.channel} · ${draft.status}", style = MaterialTheme.typography.titleMedium)
                Text("Recipient: ${draft.recipient.ifBlank { "Not specified" }}")
                Text(draft.body)
                OutlinedButton(onClick = { selectedDraft = draft }, enabled = !busy) { Text("Edit internal draft") }
            } }
        } }
        item { Text("Follow-up notices", style = MaterialTheme.typography.titleLarge) }
        snapshot.notifications.forEach { notification -> item(key = "notice-${notification.id}") {
            Card { Column(Modifier.padding(16.dp)) {
                Text("A Private AI follow-up is due.")
                Text("Notice: ${notification.status}. Review its linked action before deciding what to do.")
                if (notification.status == "pending") OutlinedButton(onClick = { onAcknowledge(notification.id) }, enabled = !busy) { Text("Acknowledge notice") }
            } }
        } }
        if (snapshot.truncated) item { Text("Only the first records are shown. Review the full list on the desktop.") }
    }
    selectedAction?.let { action ->
        var editOwner by remember(action.id) { mutableStateOf(action.owner) }
        var editDue by remember(action.id) { mutableStateOf(if (action.dueAt == null) "" else formatDeadline(action.dueAt)) }
        var completionNote by remember(action.id) { mutableStateOf("") }
        var complete by remember(action.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { selectedAction = null }, title = { Text("Review action") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action.description)
                OutlinedTextField(editOwner, { editOwner = it }, label = { Text("Owner") })
                OutlinedTextField(editDue, { editDue = it }, label = { Text("Due: yyyy-MM-dd HH:mm") })
                OutlinedTextField(completionNote, { completionNote = it }, label = { Text("Review/completion note") })
                Row { Checkbox(complete, { complete = it }); Text("Mark completed with this note") }
                Text("Completing an operational action does not establish a clinical resolution or send a draft.")
            } },
            confirmButton = { TextButton(onClick = {
                try {
                    require(!complete || completionNote.isNotBlank())
                    onUpdateAction(action, editOwner, parseDeadline(editDue), completionNote, complete); selectedAction = null
                } catch (_: Exception) { Toast.makeText(context, "Check the date and completion note.", Toast.LENGTH_SHORT).show() }
            }, enabled = !busy) { Text("Save revision") } },
            dismissButton = { TextButton(onClick = { selectedAction = null }) { Text("Cancel") } })
    }
    selectedDraft?.let { draft ->
        var recipient by remember(draft.id) { mutableStateOf(draft.recipient) }
        var body by remember(draft.id) { mutableStateOf(draft.body) }
        var subject by remember(draft.id) { mutableStateOf(draft.subject) }
        AlertDialog(onDismissRequest = { selectedDraft = null }, title = { Text("Internal ${draft.channel} draft") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(recipient, { recipient = it }, label = { Text("Recipient, if known") })
                OutlinedTextField(subject, { subject = it }, label = { Text("Subject, if needed") })
                OutlinedTextField(body, { body = it }, label = { Text("Draft text") }, minLines = 3)
                Text("This saves a review draft only. Delivery is a separate action on the desktop.")
            } },
            confirmButton = { TextButton(onClick = { onSaveDraft(draft, recipient, body, subject); selectedDraft = null }, enabled = !busy && body.isNotBlank()) { Text("Save draft") } },
            dismissButton = { TextButton(onClick = { selectedDraft = null }) { Text("Cancel") } })
    }
}

internal fun parseDeadline(value: String): Long? {
    if (value.isBlank()) return null
    val input = value.trim()
    require(input.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    val position = ParsePosition(0)
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }.parse(input, position)
    require(date != null && position.index == input.length) { "Invalid date." }
    return date.time
}
private fun formatDeadline(value: Long?): String = if (value == null) "Unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(value))
