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
    contactQrScanResult: StateFlow<String?>? = null,
    onNavigateBackToChats: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val contacts by repository.contacts.collectAsState()

    val ownNodeIdHex = remember {
        MeshServiceConnection.activeService?.ownNodeId?.joinToString("") { "%02x".format(it) } ?: ""
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showOwnQr by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showScanError by remember { mutableStateOf<String?>(null) }
    var newContactName by remember { mutableStateOf("") }
    var manualNodeId by remember { mutableStateOf("") }
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
            if (scanned.length == 16 && scanned.all { it.isLetterOrDigit() }) {
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
                    title = { Text(stringResource(R.string.n_selected, selectedContacts.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedContacts = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.contacts_title)) },
                    navigationIcon = {
                        IconButton(onClick = { onNavigateBackToChats() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        // GAP 3: scan QR code
                        IconButton(onClick = { onScanQr() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                        }
                        if (ownNodeIdHex.isNotBlank()) {
                            IconButton(onClick = { showOwnQr = true }) {
                                Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.my_qr))
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
                    Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.add_contact_desc))
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
                    Text(stringResource(R.string.no_contacts_yet), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.scan_peer_qr_or_add),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    OutlinedButton(onClick = { onScanQr() }) {
                        Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_qr_code))
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
            title = stringResource(R.string.delete_contacts_title),
            message = stringResource(R.string.remove_n_contacts, selectedContacts.size),
            confirmText = stringResource(R.string.delete), cancelText = stringResource(R.string.cancel),
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
            title = { Text(stringResource(R.string.add_contact)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newContactName,
                        onValueChange = { newContactName = it },
                        label = { Text(stringResource(R.string.contact_name_label)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = manualNodeId,
                        onValueChange = { manualNodeId = it.lowercase().filter { c -> c.isLetterOrDigit() } },
                        label = { Text(stringResource(R.string.node_id_hint)) }, singleLine = true,
                        isError = manualNodeId.isNotEmpty() && manualNodeId.length != 16,
                        supportingText = if (manualNodeId.isNotEmpty() && manualNodeId.length != 16)
                            { { Text(stringResource(R.string.node_id_length_error)) } } else null,
                        modifier = Modifier.fillMaxWidth())
                    if (manualNodeId.isEmpty()) {
                        TextButton(onClick = { showAddDialog = false; onScanQr() }) {
                            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.or_scan_qr_instead))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newContactName.isNotBlank() && manualNodeId.length == 16) {
                        repository.addContact(Contact(newContactName, manualNodeId))
                        newContactName = ""
                        manualNodeId = ""
                        showAddDialog = false
                    }
                }, enabled = newContactName.isNotBlank() && manualNodeId.length == 16) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newContactName = ""; manualNodeId = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // QR scan error
    showScanError?.let { err ->
        AlertDialog(onDismissRequest = { showScanError = null },
            title = { Text(stringResource(R.string.scan_failed)) },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { showScanError = null }) { Text(stringResource(R.string.ok)) } })
    }

    // Own QR dialog
    if (showOwnQr && ownNodeIdHex.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showOwnQr = false },
            title = { Text(stringResource(R.string.your_mesh_id)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bmp = remember(ownNodeIdHex) { BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex) }
                    bmp?.let {
                        androidx.compose.foundation.Image(bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_image_desc), modifier = Modifier.size(200.dp))
                    }
                    Text(ownNodeIdHex, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.share_qr_to_add_you),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showOwnQr = false }) { Text(stringResource(R.string.close)) } }
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
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.open_chat_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface)
    )
}
