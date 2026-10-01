package com.example.ui.screens.chats

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.speech.RecognizerIntent
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
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackClick()
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var messageInput by remember { mutableStateOf("") }
    var currentAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showModeDropdown by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Android Native Speech Recognizer Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText: ArrayList<String>? =
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spokenText?.firstOrNull()
            if (!text.isNullOrBlank()) {
                messageInput = if (messageInput.isBlank()) text else "$messageInput $text"
            }
        }
    }

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
        modifier = modifier.fillMaxSize().testTag("chat_detail_screen"),
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
                                text = "AES-256-GCM Direct Channel",
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

                        DropdownMenu(
                            expanded = showModeDropdown,
                            onDismissRequest = { showModeDropdown = false }
                        ) {
                            listOf("Local", "Smart Hybrid", "Cloud", "Local Only").forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(mode, fontWeight = FontWeight.Bold)
                                            val desc = when (mode) {
                                                "Local" -> "Inference 100% on workstation GPU"
                                                "Smart Hybrid" -> "Private AI routes between local & external"
                                                "Cloud" -> "Workstation proxies to secure cloud runtime"
                                                else -> "Strict airgapped local execution"
                                            }
                                            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        onModelModeChange(mode)
                                        showModeDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    ConnectionStatusBadge(
                        status = connectionStatus,
                        hostIp = hostIp,
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
                            text = "Offline Mode: Encrypted prompts & attachments will be queued and sent to desktop workstation upon reconnect.",
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
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak prompt for Private AI...")
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Voice input not available on this device", Toast.LENGTH_SHORT).show()
                            }
                        },
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
                                if (currentAttachment != null) "Instructions for attachment..." else "Ask local desktop AI..."
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
                                isStreaming = false
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
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop generation")
                        }
                    } else {
                        // Send Button
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    val prompt = messageInput.trim().ifBlank {
                                        if (currentAttachment?.type == "IMAGE") "Analyze this image and describe key details"
                                        else "Process this attachment and extract main insights"
                                    }
                                    onSendMessage(prompt, currentAttachment)
                                    messageInput = ""
                                    currentAttachment = null
                                }
                            },
                            enabled = canSend,
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
                                contentDescription = "Send prompt"
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
                                if (!isUser) {
                                    // Regenerate button
                                    Row(
                                        modifier = Modifier.clickable {
                                            onSendMessage("Regenerate response: $decryptedText", null)
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Regenerate",
                                            tint = CyberTeal,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "Regenerate",
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
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Synced",
                                            tint = CyberTeal,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isUser) "Synced" else "${msg.promptTokens + msg.completionTokens} tokens",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
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
                    text = "Add Attachment to Prompt",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Files and pictures are end-to-end encrypted locally and processed by your desktop AI node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action 1: Take Photo with Camera
                AttachmentOptionRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Take Picture",
                    subtitle = "Capture photo with camera to analyze or attach",
                    onClick = {
                        showAttachmentSheet = false
                        cameraLauncher.launch(null)
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
                        photoPickerLauncher.launch(
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
                    subtitle = "Select PDF, text, JSON, or code file for desktop ingestion",
                    onClick = {
                        showAttachmentSheet = false
                        documentPickerLauncher.launch("*/*")
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
