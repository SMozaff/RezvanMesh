package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.data.Contact
import com.rezvani.mesh.data.ContactsRepository
import com.rezvani.mesh.ui.components.ConfirmationDialog
import com.rezvani.mesh.utils.BarcodeUtils
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    meshConnection: MeshServiceConnection,
    onOpenChat: ((conversationId: String, contactName: String) -> Unit)? = null,
    onScanQr: () -> Unit = {},
    contactQrScanResult: StateFlow<String?>? = null
) {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val contacts by repository.contacts.collectAsState()

    val ownNodeId by MeshServiceConnection.ownNodeId.collectAsState()
    val ownNodeIdHex = ownNodeId?.joinToString("") { "%02x".format(it) }.orEmpty()

    var showAddDialog by remember { mutableStateOf(false) }
    var showOwnQr by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showScanError by remember { mutableStateOf<String?>(null) }
    var newContactName by remember { mutableStateOf("") }
    var manualNodeId by remember { mutableStateOf("") }
    val validNodeId = remember(manualNodeId) { manualNodeId.matches(Regex("^[0-9A-Fa-f]{16}$")) }
    var selectedContacts by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isInSelectionMode = selectedContacts.isNotEmpty()

    if (isInSelectionMode) {
        androidx.activity.compose.BackHandler { selectedContacts = emptySet() }
    }

    // RezvanMesh's own built-in QR scanner (CameraX + ML Kit, on-device, no
    // network calls -- see ui/screens/QrScannerScreen.kt), not the system
    // camera app and not a third-party library's generic scanner UI.
    // Navigated to via onScanQr(); the decoded value comes back through the
    // standard Compose Navigation "result" pattern (previous back-stack
    // entry's SavedStateHandle), observed here.
    val scanResult by (contactQrScanResult?.collectAsState() ?: remember { mutableStateOf(null) })
    LaunchedEffect(scanResult) {
        val scanned = scanResult?.trim()
        if (scanned != null) {
            if (scanned.matches(Regex("^[0-9A-Fa-f]{16}$"))) {
                manualNodeId = scanned.lowercase()
                showAddDialog = true
            } else {
                showScanError = "QR code doesn't contain a valid Rezvan node ID.\nScanned: \"$scanned\""
            }
        }
    }

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedContacts.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedContacts = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                TopAppBar(
                    title = { Text("Contacts") },
                    actions = {
                        // GAP 3: scan QR code
                        IconButton(onClick = { onScanQr() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        }
                        if (ownNodeIdHex.isNotBlank()) {
                            IconButton(onClick = { showOwnQr = true }) {
                                Icon(Icons.Default.QrCode, contentDescription = "My QR")
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isInSelectionMode) {
                FloatingActionButton(onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add contact")
                }
            }
        }
    ) { paddingValues ->
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.People, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("No contacts yet", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Scan a peer's QR code or tap +",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    OutlinedButton(onClick = { onScanQr() }) {
                        Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR code")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                items(contacts, key = { it.nodeIdHex }) { contact ->
                    val isSelected = contact.nodeIdHex in selectedContacts
                    ContactListItem(
                        contact = contact,
                        isSelected = isSelected,
                        isInSelectionMode = isInSelectionMode,
                        // GAP 2 + 4: tap contact -> open chat
                        onClick = {
                            if (isInSelectionMode) {
                                selectedContacts = if (isSelected)
                                    selectedContacts - contact.nodeIdHex
                                else selectedContacts + contact.nodeIdHex
                            } else {
                                onOpenChat?.invoke(contact.nodeIdHex, contact.name)
                            }
                        },
                        onLongClick = { selectedContacts = selectedContacts + contact.nodeIdHex }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp),
                        thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Delete Contacts",
            message = "Remove ${selectedContacts.size} contact(s)?",
            confirmText = "Delete", cancelText = "Cancel",
            onConfirm = {
                selectedContacts.forEach { repository.deleteContact(it) }
                selectedContacts = emptySet()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
            isDestructive = true
        )
    }

    // Add contact dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newContactName = ""; manualNodeId = "" },
            title = { Text("Add Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newContactName,
                        onValueChange = { newContactName = it },
                        label = { Text("Contact name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = manualNodeId,
                        onValueChange = {
                            manualNodeId = it.lowercase().filter { c ->
                                c.isDigit() || c in 'a'..'f'
                            }.take(16)
                        },
                        label = { Text("Node ID (16 hex chars)") }, singleLine = true,
                        isError = manualNodeId.isNotEmpty() && !validNodeId,
                        supportingText = if (manualNodeId.isNotEmpty() && !validNodeId)
                            { { Text("Must be exactly 16 hexadecimal characters") } } else null,
                        modifier = Modifier.fillMaxWidth())
                    if (manualNodeId.isEmpty()) {
                        TextButton(onClick = { showAddDialog = false; onScanQr() }) {
                            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Or scan QR code instead")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newContactName.isNotBlank() && validNodeId) {
                        repository.addContact(Contact(newContactName, manualNodeId))
                        newContactName = ""
                        manualNodeId = ""
                        showAddDialog = false
                    }
                }, enabled = newContactName.isNotBlank() && validNodeId) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newContactName = ""; manualNodeId = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR scan error
    showScanError?.let { err ->
        AlertDialog(onDismissRequest = { showScanError = null },
            title = { Text("Scan failed") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { showScanError = null }) { Text("OK") } })
    }

    // Own QR dialog
    if (showOwnQr && ownNodeIdHex.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showOwnQr = false },
            title = { Text("Your Mesh ID") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bmp = remember(ownNodeIdHex) { BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex) }
                    bmp?.let {
                        androidx.compose.foundation.Image(bitmap = it.asImageBitmap(),
                            contentDescription = "QR", modifier = Modifier.size(200.dp))
                    }
                    Text(ownNodeIdHex, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Share this QR code so others can add you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showOwnQr = false }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactListItem(contact: Contact, isSelected: Boolean,
    isInSelectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(contact.name, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(contact.nodeIdHex, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            if (isInSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                Surface(shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(contact.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        },
        trailingContent = {
            if (!isInSelectionMode) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Open chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface)
    )
}
