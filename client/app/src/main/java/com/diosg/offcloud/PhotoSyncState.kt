package com.diosg.offcloud

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registra el estado de sincronización de cada foto local.
 * localUri es la clave: identifica unívocamente el archivo en MediaStore/SAF.
 */
@Entity(tableName = "photo_sync_state")
data class PhotoSyncState(
    @PrimaryKey val localUri: String,
    val hash: String,
    val status: String, // "pending" | "uploaded" | "error"
    val lastAttempt: Long = 0L,
    val errorMsg: String? = null
)
