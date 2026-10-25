package com.example.ui.screens.meetings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.MeetingRecorder
import com.example.ui.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun MeetingsScreen(viewModel: MainViewModel) {
    com.example.ui.components.LocalDraftForm(viewModel.draftController, "meeting:new-title") { draft -> MeetingsContent(viewModel, draft) }
}

@Composable
private fun MeetingsContent(viewModel: MainViewModel, draft: com.example.ui.components.LocalDraftFormState) {
    val local by viewModel.repository.phoneMeetings.collectAsStateWithLifecycle()
    val remote by viewModel.repository.desktopMeetings.collectAsStateWithLifecycle()
    val pending by viewModel.repository.meetingPending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(com.example.ui.components.unsentForm(draft.form).optString("title")) }
    fun fields() = org.json.JSONObject().put("title", title).toString()
    com.example.ui.components.CaptureLatestDraft(draft) { fields() to null }
    var activeId by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<MeetingRecorder?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        message = if (it) "Microphone enabled. Tap Record to begin." else "Microphone permission is needed to record."
    }
    DisposableEffect(Unit) {
        onDispose { recorder?.stop(); recordingJob?.cancel(); viewModel.stopMeetingRecording = null }
    }
    LaunchedEffect(Unit) {
        try { viewModel.repository.refreshMeetings() }
        catch (e: Exception) { if (e is CancellationException) throw e; message = "Pair with the updated desktop and unlock it to refresh. Saved phone recordings remain protected." }
    }
    fun start(id: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (busy || recorder != null) return
        draft.update(fields())
        val submittedTitle = title
        val submittedForm = fields()
        busy = true
        recordingJob = scope.launch {
            var mic: MeetingRecorder? = null
            try {
                val sourceId = id ?: viewModel.repository.createMeeting(submittedTitle).also {
                    viewModel.draftController.acknowledge("meeting:new-title", submittedForm) { now, before ->
                        com.example.ui.components.acknowledgeFormFields(now, before, setOf("title")) }
                    if (title == submittedTitle) { title = ""; draft.update(fields()) }
                }
                activeId = sourceId
                mic = MeetingRecorder(); recorder = mic; busy = false
                viewModel.stopMeetingRecording = { recorder?.stop(); recordingJob?.cancel() }
                message = "Recording locally. Keep this screen open."
                mic.record { viewModel.repository.saveMeetingAudio(sourceId, it) }
                message = "Recording paused and saved. Resume or finish when ready."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "Recording stopped. Saved chunks remain protected; the latest partial chunk may be unavailable. Check microphone access, storage and vault status."
            } finally { mic?.stop(); recorder = null; busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Meetings", style = MaterialTheme.typography.headlineMedium)
        Text("Record here. Your PC transcribes offline and prepares draft notes and follow-ups. Get participants' consent before recording.")
        Text("Keep the app unlocked and this screen open. Navigation, backgrounding or the security timeout stops recording; up to 20 seconds not yet saved can be lost. Resume after unlocking. Limit: 60 minutes per meeting.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, { title = it.take(150); draft.update(fields()) }, label = { Text("Title (optional)") }, enabled = recorder == null && !busy, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { start(null) }, enabled = recorder == null && !busy) { Text("Record new meeting") }
            if (recorder != null) OutlinedButton(onClick = { busy = true; recorder?.stop() }, enabled = !busy) { Text("Pause") }
        }
        message?.let { Text(it) }
        Text("$pending pending uploads. Delivery resumes after reconnecting to the same paired PC.")
        OutlinedButton(onClick = viewModel::triggerSync, enabled = recorder == null && !busy) { Text("Sync with PC") }
        for (item in local) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    val desktop = remote.firstOrNull { it.sourceId == item.sourceId }
                    Text(if (recorder != null && activeId == item.sourceId) "Recording"
                        else if (item.reviewError != null) "Delivery requires review: ${item.reviewError}. Saved audio is retained on this phone."
                        else if (item.finishAcknowledged) "Submitted for desktop processing"
                        else if (item.finishQueued) "Finish queued on phone; awaiting desktop acknowledgment"
                        else "Saved on phone; ready to resume")
                    if (!item.finishQueued && item.reviewError == null && recorder == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { start(item.sourceId) }, enabled = !busy && item.chunks < 180) { Text("Resume") }
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                try { viewModel.repository.finishMeeting(item.sourceId); viewModel.triggerSync() }
                                catch(e: Exception) { if(e is CancellationException) throw e; message = "Could not finish. Saved audio was retained; unlock and retry." }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { Text("Finish") }
                    }
                    if (desktop != null) {
                        Text("PC: ${desktop.status}. ${desktop.pendingAudio} chunks awaiting local transcription.")
                        if (desktop.summary.isNotBlank()) Text(desktop.summary)
                        desktop.actions.forEach { Text("Proposed follow-up: $it") }
                    }
                }
            }
        }
    }
}
