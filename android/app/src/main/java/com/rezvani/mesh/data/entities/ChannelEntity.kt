package com.rezvani.mesh.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey
    val channelId: Int,
    val name: String,
    val description: String = "",
    val isPrivate: Boolean,
    val passwordHash: String? = null,
    val memberCount: Int = 0,
    val lastMessageTimestamp: Long = 0L,
    val isJoined: Boolean = false
)
