package com.rezvani.mesh.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val nodeId: String,             // 8-char hex
    val displayName: String,
    val trustLevel: Int,            // 0=VERIFIED, 1=KNOWN, 2=BLOCKED
    val lastSeen: Long,
    val publicKey: String? = null   // Base64 encoded Ed25519 public key
)

object TrustLevel {
    const val VERIFIED = 0
    const val KNOWN = 1
    const val BLOCKED = 2
}
