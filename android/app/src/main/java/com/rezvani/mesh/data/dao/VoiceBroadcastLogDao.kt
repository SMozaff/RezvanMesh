package com.rezvani.mesh.data.dao

import androidx.room.*
import com.rezvani.mesh.data.entities.VoiceBroadcastLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceBroadcastLogDao {

    @Query("SELECT * FROM voice_broadcast_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<VoiceBroadcastLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: VoiceBroadcastLogEntity)

    @Query("DELETE FROM voice_broadcast_logs WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM voice_broadcast_logs")
    suspend fun deleteAll()
}
