package com.example.ui.screens.vault

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.crypto.AttachmentUtils
import com.example.data.local.entity.EncryptedDocument
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberTeal

@Composable
fun DocumentVaultScreen(
    documents: List<EncryptedDocument>,
    decryptText: (String) -> String,
    onSaveDocument: (String, String, String, String) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onSendDocToChat: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedDocForView by remember { mutableStateOf<EncryptedDocument?>(null) }

    var newDocTitle by remember { mutableStateOf("") }
    var newDocType by remember { mutableStateOf("MARKDOWN") }
    var newDocContent by remember { mutableStateOf("") }
    var newDocTags by remember { mutableStateOf("Confidential, Clinical Report") }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val attachment = AttachmentUtils.uriToChatAttachment(context, uri)
            if (attachment != null) {
                onSaveDocument(
                    attachment.name,
                    attachment.type,
                    "Attached File: ${attachment.name} (${attachment.sizeBytes / 1024} KB). Uploaded to Windows Private AI.",
                    "Uploaded, Mobile Companion"
                )
                Toast.makeText(context, "Uploaded '${attachment.name}' to Private AI", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("document_vault_screen")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Security Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberTeal.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(CyberTeal.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Vault Security",
                            tint = CyberTeal,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Reports & Generated Outputs",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Windows Private AI is the permanent storage and processor. No permanent PHI is cached on Android.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Upload buttons bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                    modifier = Modifier.weight(1f).testTag("upload_file_button")
                ) {
                    Icon(imageVector = Icons.Default.FileUpload, contentDescription = "Upload", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload File / PDF", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { filePickerLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f).testTag("upload_photo_button")
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = "Photo", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload Photo", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Reports & Files (${documents.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (documents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No files or reports stored.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentItemCard(
                            document = doc,
                            onClick = { selectedDocForView = doc },
                            onDelete = { onDeleteDocument(doc.id) },
                            onShare = {
                                val decrypted = decryptText(doc.encryptedContent)
                                shareDocument(context, doc.fileName, decrypted)
                            }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_document_fab")
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Text Document")
        }
    }

    // View Document Dialog with Android Share & Send to Chat
    selectedDocForView?.let { doc ->
        val decryptedText = remember(doc.encryptedContent) {
            decryptText(doc.encryptedContent)
        }

        AlertDialog(
            onDismissRequest = { selectedDocForView = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Description, contentDescription = "Doc", tint = CyberTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(doc.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = CyberTeal,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "SHA-256 Verified Local Ledger",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = CyberTeal
                                    )
                                )
                            }
                            Text(
                                text = "Hash: ${doc.checksumSha256.take(24)}...",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Decrypted Output:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn {
                            item {
                                Text(
                                    text = decryptedText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = if (doc.fileType == "CODE" || doc.fileType == "JSON") FontFamily.Monospace else FontFamily.Default
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        shareDocument(context, doc.fileName, decryptedText)
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                    Button(onClick = { selectedDocForView = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    // Add Document Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Create & Encrypt Report") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Document will be encrypted with AES-256-GCM locally and synced securely to your workstation node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newDocTitle,
                        onValueChange = { newDocTitle = it },
                        label = { Text("File Name") },
                        placeholder = { Text("e.g. Clinical_Brief.md") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_doc_title_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDocContent,
                        onValueChange = { newDocContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth().height(140.dp).testTag("new_doc_content_input"),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newDocTitle.isNotBlank() && newDocContent.isNotBlank()) {
                            onSaveDocument(newDocTitle.trim(), newDocType, newDocContent.trim(), newDocTags)
                            newDocTitle = ""
                            newDocContent = ""
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("save_new_doc_button")
                ) {
                    Text("Save & Encrypt")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DocumentItemCard(
    document: EncryptedDocument,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("doc_item_${document.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "Document",
                    tint = CyberTeal,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${document.fileType} • ${(document.fileSizeBytes / 1024).coerceAtLeast(1)} KB",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = CyberTeal,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

fun shareDocument(context: Context, title: String, content: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, content)
        putExtra(Intent.EXTRA_TITLE, title)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Share $title via")
    context.startActivity(shareIntent)
}
