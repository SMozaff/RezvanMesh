// android/app/src/main/java/com/rezvani/mesh/data/ContactsRepository.kt

package com.rezvani.mesh.data

import android.content.Context
import android.util.Log
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.TrustLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contact list, backed by the SQLCipher-encrypted Room database.
 *
 * # Why this no longer uses `contacts.txt`
 *
 * The previous implementation kept contacts in a pipe-delimited plaintext file
 * in `filesDir`:
 *
 * ```text
 * Alice|0011223344556677
 * ```
 *
 * For a privacy-oriented mesh app that is a straightforward disclosure: the
 * file sat unencrypted next to the encrypted database, so anyone who recovered
 * one artifact (a backup, a rooted device, an `adb backup`, a filesystem dump
 * after the screen lock) got the full social graph even if the database stayed
 * closed. It also had no `lastSeen`, no trust level, and no NodeId validation.
 *
 * `ContactEntity` and `ContactDao` already existed and modelled all of that --
 * they simply had no callers. This class is now the thing that uses them, so
 * "is it encrypted" stops being a per-repository decision.
 *
 * # Migration
 *
 * Existing `contacts.txt` is imported once on first access and then deleted, so
 * upgrading does not silently drop the user's contacts. The import is
 * deliberately lenient about the format and strict about the data: a display
 * name containing `|` used to make the whole line unparseable and vanish, and a
 * malformed NodeId must not become a primary key that later message routing
 * would try to address.
 */
class ContactsRepository(context: Context) {

    // Room holds the instance for the process lifetime; it must not be given a
    // short-lived Activity context.
    private val appContext: Context = context.applicationContext

    private val dbPassphrase: ByteArray by lazy {
        DbKeyProvider.getOrCreateKey(appContext)
    }

    private val contactDao: ContactDao by lazy {
        AppDatabase.getInstance(appContext, dbPassphrase).contactDao()
    }

    /**
     * Contacts, excluding blocked ones.
     *
     * Nothing marks a contact blocked today, so this is identical to "all";
     * using the filtered query means the list starts respecting the flag for
     * free when blocking is ever wired up, rather than showing someone the
     * contact they blocked.
     */
    val contacts: StateFlow<List<Contact>> = contactDao
        .getNonBlockedContacts()
        .map { entities -> entities.map { Contact(it.displayName, it.nodeId) } }
        .stateIn(SharedScope, SharingStarted.Eagerly, emptyList())

    init {
        // One-time import from the old plaintext file. Guarded by a process-wide
        // flag: this class is constructed once per consumer (there is a
        // ChatsViewModel and a ContactsScreen, each holding their own), and
        // without the guard each instance would race to import the same file.
        // The observable flow is backed by Room and repopulates itself as soon
        // as the import commits, so there is nothing to await.
        if (ImportStarted.compareAndSet(false, true)) {
            SharedScope.launch { importLegacyFileOnce() }
        }
    }

    /**
     * Add a contact, ignoring a duplicate NodeId.
     *
     * A duplicate would be a primary-key conflict and abort the whole insert, so
     * this keeps the previous "first one wins" behaviour rather than letting
     * the caller see a crash-equivalent.
     */
    fun addContact(contact: Contact) {
        SharedScope.launch {
            val nodeId = contact.nodeIdHex.trim()
            if (!isValidNodeId(nodeId)) {
                Log.w(TAG, "Refusing to store contact with invalid NodeId '$nodeId'")
                return@launch
            }
            if (contactDao.getContactById(nodeId) != null) {
                Log.i(TAG, "Contact $nodeId already present, not overwriting")
                return@launch
            }
            contactDao.insert(
                ContactEntity(
                    nodeId = nodeId,
                    displayName = contact.name.trim(),
                    trustLevel = TrustLevel.KNOWN,
                    lastSeen = 0L
                )
            )
        }
    }

    fun deleteContact(nodeIdHex: String) {
        SharedScope.launch { contactDao.deleteById(nodeIdHex.trim()) }
    }

    /**
     * Import the legacy plaintext contact file exactly once, then delete it.
     *
     * Idempotent: after the file is gone there is nothing to do, so a second
     * run is a cheap `exists()` check. It is safe to run concurrently with
     * [addContact] because imports are keyed on the primary key and the DAO
     * ignores a row that already exists.
     */
    private suspend fun importLegacyFileOnce() = withContext(Dispatchers.IO) {
        val legacy = File(appContext.filesDir, LEGACY_FILE_NAME)
        if (!legacy.exists()) return@withContext

        var imported = 0
        var skipped = 0
        try {
            legacy.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                // limit = 2 so a display name containing '|' keeps its value
                // instead of making the line unparseable. Previously such a
                // line was dropped on every load, so the contact was lost the
                // next time the app started.
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) {
                    skipped++
                    return@forEach
                }
                val name = parts[0].trim()
                val nodeId = parts[1].trim()
                if (name.isEmpty() || !isValidNodeId(nodeId)) {
                    skipped++
                    return@forEach
                }
                if (contactDao.getContactById(nodeId) != null) {
                    skipped++
                    return@forEach
                }
                contactDao.insert(
                    ContactEntity(
                        nodeId = nodeId,
                        displayName = name,
                        trustLevel = TrustLevel.KNOWN,
                        lastSeen = 0L
                    )
                )
                imported++
            }
        } catch (e: Exception) {
            // Leave the file in place so a later attempt can retry rather than
            // losing the contacts to a transient failure.
            Log.e(TAG, "Legacy contact import failed; keeping $LEGACY_FILE_NAME for retry", e)
            return@withContext
        }

        // The import completed, so the plaintext file has served its purpose
        // and leaving it around would defeat the point of the migration.
        if (legacy.delete()) {
            Log.i(TAG, "Imported $imported contact(s) from $LEGACY_FILE_NAME (skipped $skipped); plaintext file removed")
        } else {
            Log.w(TAG, "Imported $imported contact(s), but could not delete $LEGACY_FILE_NAME -- remove it manually")
        }
    }

    /**
     * A NodeId is the 8-byte mesh address rendered as 16 lowercase hex
     * characters. Rejecting anything else keeps a typo out of a primary key
     * that message routing would later treat as a destination.
     */
    private fun isValidNodeId(value: String): Boolean =
        value.length == NODE_ID_HEX_LENGTH && value.all { it.isDigit() || it in "a".."f" || it in "A".."F" }

    private companion object {
        const val TAG = "ContactsRepository"
        const val LEGACY_FILE_NAME = "contacts.txt"
        const val NODE_ID_HEX_LENGTH = 16

        /**
         * One scope for the whole process.
         *
         * This class is instantiated once per consumer rather than being a
         * singleton, and each instance previously created its own
         * `SupervisorJob` that nothing ever cancelled -- so a rotation that
         * rebuilt the composable left another live scope behind. Sharing one
         * bounds it to exactly one for the process lifetime, which is what the
         * fire-and-forget writes actually need.
         */
        val SharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Ensures the legacy import runs at most once per process. */
        val ImportStarted = AtomicBoolean(false)
    }
}
