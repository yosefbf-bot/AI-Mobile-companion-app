package com.example.ui.screens.chats

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.example.ui.components.*
import org.json.JSONObject
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.crypto.AttachmentUtils
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.model.ChatAttachment
import com.example.data.model.ChatSubmissionReceipt
import com.example.data.model.ConnectionStatus
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.StatusAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    session: EncryptedChatSession?,
    messages: List<EncryptedChatMessage>,
    connectionStatus: ConnectionStatus,
    hostIp: String,
    modelMode: String,
    onModelModeChange: (String) -> Unit,
    decryptText: (String) -> String,
    decryptAttachmentBitmap: (String) -> Bitmap?,
    onSendMessage: (String, ChatAttachment?) -> Unit,
    onStopStreaming: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPickEvidence: ((String) -> Unit)? = null, onTakePhoto: (() -> Unit)? = null,
    incomingEvidence: ChatAttachment? = null, onConsumeEvidence: () -> Unit = {},
    loadUnsentDraft: () -> String = { "" }, onUnsentDraftChange: (String) -> Unit = {},
    isRequestRunning: Boolean = false,
    lastSavedSubmission: ChatSubmissionReceipt? = null,
    submissionFinished: Long = 0,
    snackbarHost: @Composable () -> Unit = {},
    lastConnectionCheck: Long = 0,
    isSyncing: Boolean = false,
    onCheckConnection: () -> Unit = {},
    onAskAgain: (EncryptedChatMessage) -> Unit = {}
) {
    BackHandler {
        onBackClick()
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val restored = remember(session?.id) { unsentForm(loadUnsentDraft()) }
    var messageInput by remember(session?.id) { mutableStateOf(restored.optString("text")) }
    var currentAttachment by remember(session?.id) { mutableStateOf(formAttachment(restored)) }
    LaunchedEffect(messageInput,currentAttachment) { onUnsentDraftChange(JSONObject().put("text",messageInput).put("evidence",attachmentForm(currentAttachment)).toString()) }
    LaunchedEffect(incomingEvidence) { incomingEvidence?.let { currentAttachment = it; onConsumeEvidence() } }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showModeDropdown by remember { mutableStateOf(false) }
    val isStreaming = isRequestRunning
    var pendingSubmission by remember(session?.id) { mutableStateOf<PendingChatSubmission?>(null) }
    LaunchedEffect(lastSavedSubmission, submissionFinished) {
        pendingSubmission?.let { submitted ->
            if (lastSavedSubmission != null && lastSavedSubmission.sessionId == session?.id &&
                lastSavedSubmission.id != submitted.priorReceiptId) {
                // A receipt acknowledges the durable local commit, independently of later delivery.
                // Compare the raw submitted values so later edits/evidence are never cleared.
                if (messageInput == submitted.text) messageInput = ""
                if (currentAttachment == submitted.attachment) currentAttachment = null
                pendingSubmission = null
            } else if (submissionFinished != submitted.priorCompletion) {
                // Saving failed: retain both draft fields and allow an explicit retry.
                pendingSubmission = null
            }
        }
    }

    val listState = rememberLazyListState()

    val dictate = rememberOnDeviceDictation { messageInput = if (messageInput.isBlank()) it else "$messageInput $it" }

    // 1. Camera Picture Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            currentAttachment = AttachmentUtils.bitmapToChatAttachment(bitmap)
        }
    }

    // 2. Photo Library Picker Launcher (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            currentAttachment = AttachmentUtils.uriToChatAttachment(context, uri)
        }
    }

    // 3. Document / PDF / Code Picker Launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            currentAttachment = AttachmentUtils.uriToChatAttachment(context, uri)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        // Resize the whole conversation above the IME, so the fixed composer
        // and scrollable history share the remaining window instead of overlapping it.
        modifier = modifier.fillMaxSize().imePadding().testTag("chat_detail_screen"),
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "Encrypted Chat",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "E2EE",
                                tint = CyberTeal,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Encrypted phone storage · paired HTTPS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("chat_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to chats"
                        )
                    }
                },
                actions = {
                    // Processing Mode Selector (Local, Smart Hybrid, Cloud, Local Only)
                    Box {
                        SuggestionChip(
                            onClick = { showModeDropdown = true },
                            label = { Text(modelMode, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            icon = { Icon(Icons.Default.Tune, contentDescription = "Mode", modifier = Modifier.size(14.dp)) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = CyberTeal.copy(alpha = 0.15f),
                                labelColor = CyberTeal
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = CyberTeal.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.height(28.dp).testTag("model_mode_chip")
                        )

                        DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false }) {
                            DropdownMenuItem(text = { Text("Processing is configured on the PC. Clinical privacy requests local-only HIPAA-mode chat.") },
                                onClick = { showModeDropdown = false })
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    ConnectionStatusBadge(
                        status = if (isSyncing) ConnectionStatus.SYNCING else connectionStatus,
                        hostIp = hostIp,
                        lastCheckedAt = lastConnectionCheck,
                        onClick = onCheckConnection,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Offline notice if disconnected
                if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(StatusAmber.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Text requests wait for desktop delivery. Attachments save as evidence notes in Notes/Library for human review.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Active attachment preview container
                currentAttachment?.let { attachment ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("attachment_preview_card"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberTeal.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (attachment.type == "IMAGE" && attachment.previewBitmap != null) {
                                Image(
                                    bitmap = attachment.previewBitmap.asImageBitmap(),
                                    contentDescription = "Attachment Thumbnail",
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberTeal.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "File Icon",
                                        tint = CyberTeal,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = attachment.name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${attachment.type} • ${(attachment.sizeBytes / 1024).coerceAtLeast(1)} KB",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

                            IconButton(
                                onClick = { currentAttachment = null },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("remove_attachment_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Input row with attachment, microphone, text field, and send/stop button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAttachmentSheet = true },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("open_attachment_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Add attachment or take photo",
                            tint = if (currentAttachment != null) CyberTeal else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Native Speech Input Button
                    IconButton(
                        onClick = dictate,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("voice_input_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice to text",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        placeholder = {
                            Text(
                                if (currentAttachment != null) "Note for evidence review..." else "Ask local desktop AI..."
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = messageInput.isNotBlank() || currentAttachment != null

                    if (isStreaming) {
                        // Stop Generation Button
                        IconButton(
                            onClick = {
                                onStopStreaming()
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = StatusAmber,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .testTag("chat_stop_button")
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop request")
                        }
                    } else {
                        // Send Button
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    val prompt = messageInput.trim().ifBlank {
                                        "Evidence supplied for human review. Image contents were not interpreted."
                                    }
                                    // Store the exact submission synchronously before dispatch, using
                                    // the same encrypted-RAM callback as ordinary draft changes.
                                    onUnsentDraftChange(JSONObject().put("text", messageInput)
                                        .put("evidence", attachmentForm(currentAttachment)).toString())
                                    pendingSubmission = PendingChatSubmission(messageInput, currentAttachment,
                                        lastSavedSubmission?.id, submissionFinished)
                                    onSendMessage(prompt, currentAttachment)
                                }
                            },
                            enabled = canSend && pendingSubmission == null,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .testTag("chat_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (currentAttachment != null) "Save evidence note" else "Send prompt"
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isUser = msg.sender == "user"
                val decryptedText = decryptText(msg.encryptedContent)
                // Locally acknowledged reply IDs retain an exact original request/policy link.
                // Imported history lacks that policy provenance, so it does not offer this action.
                val originalQuestion = if (msg.sender == "assistant" && msg.status == "SYNCED_DESKTOP" && msg.id.startsWith("reply-"))
                    messages.firstOrNull { it.id == msg.id.removePrefix("reply-") && it.sender == "user" &&
                        it.status == "SYNCED_DESKTOP" && !it.desktopFingerprint.isNullOrBlank() &&
                        it.attachmentName == null && it.attachmentDataEncrypted == null } else null

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .testTag("chat_message_card_${msg.id}"),
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) else null
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Assistant Header
                            if (!isUser) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Decrypted",
                                            tint = CyberCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = msg.modelName,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = CyberTeal
                                            )
                                        )
                                    }

                                    // Copy action
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(decryptedText))
                                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy message",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            // Attached media rendering
                            if (!msg.attachmentName.isNullOrBlank()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        if (msg.attachmentType == "IMAGE" && msg.attachmentDataEncrypted != null) {
                                            val imageBitmap = remember(msg.attachmentDataEncrypted) {
                                                decryptAttachmentBitmap(msg.attachmentDataEncrypted)
                                            }
                                            if (imageBitmap != null) {
                                                Image(
                                                    bitmap = imageBitmap.asImageBitmap(),
                                                    contentDescription = "Attached Photo",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(180.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (msg.attachmentType == "IMAGE") Icons.Default.PhotoLibrary else Icons.Default.Description,
                                                contentDescription = "Attachment",
                                                tint = CyberTeal,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = msg.attachmentName,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // Main Text Content
                            Text(
                                text = decryptedText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (originalQuestion != null) {
                                    Row(
                                        modifier = Modifier.testTag("chat_ask_again_${msg.id}")
                                            .clickable(enabled = !isRequestRunning && pendingSubmission == null) { onAskAgain(originalQuestion) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Ask original question again as a new request",
                                            tint = CyberTeal,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "Ask again · new request",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = CyberTeal)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }

                                if (msg.status == "QUEUED_OFFLINE") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = "Queued Offline",
                                            tint = StatusAmber,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Queued for Desktop Sync",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                color = StatusAmber
                                            )
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (msg.status == "SYNCED_DESKTOP") Icons.Default.CheckCircle else Icons.Default.Schedule,
                                            contentDescription = if (msg.status == "SYNCED_DESKTOP") "Synced" else "Delivery unconfirmed",
                                            tint = CyberTeal,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = when (msg.status) {
                                                "SYNCED_DESKTOP" -> if (isUser) "Desktop acknowledged" else "Desktop reply"
                                                "REVIEW_REQUIRED" -> "Review required — delivery unconfirmed"
                                                "DEMO" -> "Demo only"
                                                else -> "Pending or unverified delivery"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
                            }
                            if (isUser && msg.status in setOf("QUEUED_OFFLINE", "REVIEW_REQUIRED") && !msg.deliveryError.isNullOrBlank()) {
                                Text(msg.deliveryError, modifier = Modifier.testTag("chat_delivery_error_${msg.id}"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Attachment Actions
    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Add evidence note from chat",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "This saves an evidence note in Notes/Library for human review over paired HTTPS. Files are not uploaded into chat or interpreted by the text model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action 1: Take Photo with Camera
                AttachmentOptionRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Take Picture",
                    subtitle = "Capture a preview photo as evidence for review",
                    onClick = {
                        showAttachmentSheet = false
                        onTakePhoto?.invoke() ?: cameraLauncher.launch(null)
                    },
                    testTag = "attachment_option_camera"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action 2: Pick Visual Media from Gallery
                AttachmentOptionRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Choose Photo or Image",
                    subtitle = "Select screenshots, diagrams, or photos via photo picker",
                    onClick = {
                        showAttachmentSheet = false
                        onPickEvidence?.invoke("image/*") ?: photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    testTag = "attachment_option_photo"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action 3: Document / PDF / Code
                AttachmentOptionRow(
                    icon = Icons.Default.Description,
                    title = "Attach Document, PDF or Code",
                    subtitle = "JPEG, PNG, PDF or TXT evidence, up to 5 MiB",
                    onClick = {
                        showAttachmentSheet = false
                        onPickEvidence?.invoke("*/*") ?: documentPickerLauncher.launch("*/*")
                    },
                    testTag = "attachment_option_file"
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun AttachmentOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CyberTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = CyberTeal,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class PendingChatSubmission(
    val text: String,
    val attachment: ChatAttachment?,
    val priorReceiptId: String?,
    val priorCompletion: Long
)
