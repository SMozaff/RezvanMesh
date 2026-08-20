package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.Contact
import com.rezvani.mesh.data.ContactsRepository
import com.rezvani.mesh.radio.SendResult
import com.rezvani.mesh.radio.failureMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(meshConnection: MeshServiceConnection) {
    val messages by meshConnection.receivedMessages.collectAsState()
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val contacts by repository.contacts.collectAsState()
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var text by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mesh Messages", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        var dropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedContact?.name ?: "Select contact",
                onValueChange = {},
                readOnly = true,
                label = { Text("Recipient") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                contacts.forEach { contact ->
                    DropdownMenuItem(
                        text = { Text(contact.name) },
                        onClick = {
                            selectedContact = contact
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            selectedContact?.let { contact ->
                val nodeIdBytes = try {
                    contact.nodeIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } catch (_: Exception) { null }
                if (nodeIdBytes != null) {
                    coroutineScope.launch {
                        val result = meshConnection.sendTextMessage(nodeIdBytes, text)
                        if (result is SendResult.Queued) {
                            text = ""
                        } else {
                            sendError = result.failureMessage()
                        }
                    }
                } else {
                    sendError = "Selected contact has an invalid mesh ID"
                }
            }
        }, enabled = selectedContact != null && text.isNotBlank()) {
            Text("Send")
        }
        sendError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Received:", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(messages, key = { "${it.senderId.joinToString("")}${it.timestamp}" }) { msg ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("From: ${msg.senderId.joinToString("") { "%02x".format(it) }}")
                        Text("Type: ${if (msg.messageType == 3.toByte()) "EMERGENCY" else "Text"}")
                        Text("Content: ${String(msg.content)}")
                    }
                }
            }
        }
    }
}