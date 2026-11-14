package com.diosg.offcloud

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.Request

/**
 * Baja una foto del server y la escribe en la carpeta local vinculada
 * (árbol SAF). Si falla a medias, borra el archivo parcial y reintenta.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverUrl = inputData.getString(KEY_SERVER_URL) ?: return Result.failure()
        val token = SessionStore.getToken(applicationContext) ?: return Result.failure()
        val photoId = inputData.getInt(KEY_PHOTO_ID, -1).takeIf { it >= 0 } ?: return Result.failure()
        val treeUri = inputData.getString(KEY_TREE_URI)?.let { Uri.parse(it) } ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: "photo_$photoId.jpg"

        val folder = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return Result.failure()
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
            ?: "image/jpeg"

        var target: DocumentFile? = null
        return try {
            val request = Request.Builder()
                .url("$serverUrl/photos/$photoId/file")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            UploadWorker.transferClient.newCall(request).execute().use { response ->
                // 404: la foto se borró del server entre medias, no tiene sentido reintentar.
                if (response.code == 404) return Result.failure()
                if (!response.isSuccessful) return Result.retry()
                val body = response.body ?: return Result.retry()

                // Si ya existe un archivo con ese nombre, el proveedor SAF
                // le añade un sufijo " (1)" en vez de pisarlo.
                target = folder.createFile(mimeType, filename) ?: return Result.failure()
                applicationContext.contentResolver.openOutputStream(target!!.uri)?.use { output ->
                    body.byteStream().copyTo(output)
                } ?: run {
                    target?.delete()
                    return Result.failure()
                }
            }
            android.util.Log.d("OffCloud", "Descargada $filename en ${treeUri.lastPathSegment}")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("OffCloud", "Descarga de $filename falló: ${e.message}", e)
            target?.delete()
            Result.retry()
        }
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_PHOTO_ID = "photo_id"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_FILENAME = "filename"
        const val TAG = "offcloud_download"
    }
}
