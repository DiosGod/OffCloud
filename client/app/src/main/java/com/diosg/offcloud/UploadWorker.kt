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
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: "photo.jpg"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: "android-client"
        val hash = inputData.getString(KEY_HASH) ?: ""

        val uri = Uri.parse(uriString)
        val dao = AppDatabase.getInstance(applicationContext).photoSyncDao()
        val tempFile = File.createTempFile("upload_", "_$filename", applicationContext.cacheDir)

        return try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return Result.failure()

            val mediaType = mimeType.toMediaTypeOrNull()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, tempFile.asRequestBody(mediaType))
                .addFormDataPart("device_id", deviceId)
                .build()

            val request = Request.Builder()
                .url("$serverUrl/photos/upload")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val success = OkHttpClient().newCall(request).execute().use { it.isSuccessful }

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
        const val KEY_TOKEN = "token"
        const val KEY_FILENAME = "filename"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HASH = "hash"
        const val TAG = "offcloud_upload"
    }
}
