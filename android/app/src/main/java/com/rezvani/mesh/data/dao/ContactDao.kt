package com.rezvani.mesh.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rezvani.mesh.data.entities.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Insert
    suspend fun insert(contact: ContactEntity)

    @Insert
    suspend fun insertAll(contacts: List<ContactEntity>)

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId")
    suspend fun getContactById(nodeId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId")
    fun getContactByIdFlow(nodeId: String): Flow<ContactEntity?>

    @Query("""
        SELECT * FROM contacts 
        WHERE displayName LIKE '%' || :query || '%' 
        OR nodeId LIKE '%' || :query || '%'
        ORDER BY displayName COLLATE NOCASE ASC
    """)
    fun searchContacts(query: String): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET trustLevel = :trustLevel WHERE nodeId = :nodeId")
    suspend fun updateTrustLevel(nodeId: String, trustLevel: Int)

    @Query("UPDATE contacts SET lastSeen = :timestamp WHERE nodeId = :nodeId")
    suspend fun updateLastSeen(nodeId: String, timestamp: Long)

    @Query("UPDATE contacts SET displayName = :displayName WHERE nodeId = :nodeId")
    suspend fun updateDisplayName(nodeId: String, displayName: String)

    @Query("DELETE FROM contacts WHERE nodeId = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM contacts WHERE trustLevel = 2")
    suspend fun deleteBlockedContacts()

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int

    @Query("SELECT * FROM contacts WHERE trustLevel != 2")
    fun getNonBlockedContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE trustLevel = 0")
    fun getVerifiedContacts(): Flow<List<ContactEntity>>
}
