package com.diosg.offcloud

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PhotoSyncState::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoSyncDao(): PhotoSyncDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offcloud.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
