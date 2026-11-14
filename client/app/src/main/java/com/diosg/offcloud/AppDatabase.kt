package com.diosg.offcloud

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhotoSyncState::class, LocalFileHash::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoSyncDao(): PhotoSyncDao

    abstract fun localFileHashDao(): LocalFileHashDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v2: tabla de caché de hashes (carpetas multi-dispositivo).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_file_hash` (" +
                        "`uri` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, `hash` TEXT NOT NULL, " +
                        "PRIMARY KEY(`uri`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offcloud.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
