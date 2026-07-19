package com.rezvani.mesh.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_broadcast_logs")
data class VoiceBroadcastLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val senderNodeId: String, // hex string
    val severity: Int,
    val codec: Int, // 0=AMR_WB, 1=Opus
    val rssi: Int,
    val audioFilePath: String,
    val durationMs: Int,
    val isIncoming: Boolean
)
