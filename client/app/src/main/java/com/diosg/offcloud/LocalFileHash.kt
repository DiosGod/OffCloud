package com.diosg.offcloud

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Caché de hashes de archivos locales. Comprobar si una carpeta está al día
 * exige el hash de cada foto; sin caché habría que leer todos los archivos
 * cada vez. Si el tamaño o la fecha de modificación cambian, se recalcula.
 */
@Entity(tableName = "local_file_hash")
data class LocalFileHash(
    @PrimaryKey val uri: String,
    val size: Long,
    val lastModified: Long,
    val hash: String
)

@Dao
interface LocalFileHashDao {

    @Query("SELECT * FROM local_file_hash WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): LocalFileHash?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LocalFileHash)
}
