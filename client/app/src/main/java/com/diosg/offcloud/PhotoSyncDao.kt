package com.diosg.offcloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoSyncDao {

    @Query("SELECT * FROM photo_sync_state")
    fun getAllFlow(): Flow<List<PhotoSyncState>>

    @Query("SELECT * FROM photo_sync_state WHERE localUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PhotoSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PhotoSyncState)
}
