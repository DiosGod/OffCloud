package com.diosg.offcloud

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Sube una única foto al server. WorkManager se encarga de reintentar
 * automáticamente (con backoff) si devolvemos Result.retry(), por ejemplo
 * cuando no hay red o el server no responde.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val serverUrl = inputData.getString(KEY_SERVER_URL) ?: return Result.failure()
        // Sin sesión (se cerró sesión con subidas en cola): no tiene sentido reintentar.
        val token = SessionStore.getToken(applicationContext) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: "photo.jpg"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: "android-client"
        val hash = inputData.getString(KEY_HASH) ?: ""
        val takenAt = inputData.getString(KEY_TAKEN_AT)
        val folderId = inputData.getInt(KEY_FOLDER_ID, -1)

        val uri = Uri.parse(uriString)
        val dao = AppDatabase.getInstance(applicationContext).photoSyncDao()
        val tempFile = File.createTempFile("upload_", "_$filename", applicationContext.cacheDir)

        return try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return Result.failure()

            val mediaType = mimeType.toMediaTypeOrNull()
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, tempFile.asRequestBody(mediaType))
                .addFormDataPart("device_id", deviceId)
            if (!takenAt.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("taken_at", takenAt)
            }
            if (folderId >= 0) {
                bodyBuilder.addFormDataPart("folder_id", folderId.toString())
            }
            val requestBody = bodyBuilder.build()

            val request = Request.Builder()
                .url("$serverUrl/photos/upload")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val success = transferClient.newCall(request).execute().use { it.isSuccessful }

            if (success) {
                dao.upsert(
                    PhotoSyncState(
                        localUri = uriString,
                        hash = hash,
                        status = "uploaded",
                        lastAttempt = System.currentTimeMillis()
                    )
                )
                Result.success()
            } else {
                dao.upsert(
                    PhotoSyncState(
                        localUri = uriString,
                        hash = hash,
                        status = "error",
                        lastAttempt = System.currentTimeMillis(),
                        errorMsg = "El server respondió con error"
                    )
                )
                Result.retry()
            }
        } catch (e: Exception) {
            dao.upsert(
                PhotoSyncState(
                    localUri = uriString,
                    hash = hash,
                    status = "error",
                    lastAttempt = System.currentTimeMillis(),
                    errorMsg = e.message
                )
            )
            Result.retry()
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_FILENAME = "filename"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HASH = "hash"
        const val KEY_TAKEN_AT = "taken_at"
        const val KEY_FOLDER_ID = "folder_id"
        const val TAG = "offcloud_upload"

        /**
         * Cliente para subir/bajar fotos. Los timeouts por defecto de OkHttp
         * (10 s) se quedan cortos: una foto de ~6 MB por datos móviles a
         * través de Tailscale los superaba y la subida fallaba siempre con
         * "timeout". Compartido también por DownloadWorker.
         */
        val transferClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}
