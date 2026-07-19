// android/app/src/main/java/com/rezvani/mesh/data/ContactsRepository.kt

package com.rezvani.mesh.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ContactsRepository(private val context: Context) {

    private val contactsFile: File
        get() = File(context.filesDir, "contacts.txt")

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            _contacts.value = loadFromFile()
        }
    }

    private fun loadFromFile(): List<Contact> {
        if (!contactsFile.exists()) return emptyList()
        return contactsFile.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size == 2) Contact(parts[0], parts[1]) else null
        }
    }

    private fun saveToFile(contacts: List<Contact>) {
        contactsFile.bufferedWriter().use { writer ->
            contacts.forEach { contact ->
                writer.write("${contact.name}|${contact.nodeIdHex}\n")
            }
        }
    }

    fun addContact(contact: Contact) {
        val current = _contacts.value.toMutableList()
        if (current.none { it.nodeIdHex == contact.nodeIdHex }) {
            current.add(contact)
            _contacts.value = current
            scope.launch { saveToFile(current) }
        }
    }

    fun deleteContact(nodeIdHex: String) {
        val current = _contacts.value.toMutableList()
        current.removeAll { it.nodeIdHex == nodeIdHex }
        _contacts.value = current
        scope.launch { saveToFile(current) }
    }
}