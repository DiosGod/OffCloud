package com.diosg.offcloud

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Carpeta lógica en el server (compartida entre dispositivos de la misma cuenta). */
data class CloudFolder(
    val id: Int,
    val name: String,
    val photoCount: Int,
    val totalSizeBytes: Long
)

data class CloudPhoto(val id: Int, val hash: String, val filename: String)

data class LocalPhoto(val doc: DocumentFile, val hash: String)

/** Estado de una carpeta de la nube vista desde ESTE dispositivo. */
sealed class FolderStatus {
    object Unlinked : FolderStatus()
    object Checking : FolderStatus()
    data class Error(val message: String) : FolderStatus()
    data class Ready(val toUpload: Int, val toDownload: Int) : FolderStatus() {
        val upToDate get() = toUpload == 0 && toDownload == 0
    }
}

private val http = OkHttpClient()
private val JSON_TYPE = "application/json".toMediaType()

/**
 * Qué carpeta local está vinculada a cada carpeta de la nube en este
 * dispositivo. Va por cuenta (server + usuario), porque los ids de carpeta
 * solo tienen sentido dentro de un server y un usuario concretos.
 */
class FolderLinks(private val prefs: SharedPreferences, serverUrl: String, username: String) {
    private val key = "folder_links|$serverUrl|$username"

    fun all(): Map<Int, Uri> {
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        return json.keys().asSequence().associate { it.toInt() to Uri.parse(json.getString(it)) }
    }

    fun set(folderId: Int, treeUri: Uri) = save(all() + (folderId to treeUri))

    fun remove(folderId: Int) = save(all() - folderId)

    private fun save(links: Map<Int, Uri>) {
        val json = JSONObject()
        links.forEach { (id, uri) -> json.put(id.toString(), uri.toString()) }
        prefs.edit().putString(key, json.toString()).apply()
    }
}

// --- API de carpetas ---

private fun authed(serverUrl: String, token: String, path: String) =
    Request.Builder().url("$serverUrl$path").header("Authorization", "Bearer $token")

/** GET /folders. Null si falla la conexión o el token ya no vale. */
fun fetchFolders(serverUrl: String, token: String): List<CloudFolder>? {
    return try {
        http.newCall(authed(serverUrl, token, "/folders").get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val arr = JSONArray(response.body?.string() ?: return null)
            (0 until arr.length()).map { parseFolder(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "Listar carpetas falló: ${e.message}", e)
        null
    }
}

/** POST /folders. Si ya existe una carpeta con ese nombre, el server devuelve la misma. */
fun createFolder(serverUrl: String, token: String, name: String): CloudFolder? {
    return try {
        val body = JSONObject().put("name", name).toString().toRequestBody(JSON_TYPE)
        http.newCall(authed(serverUrl, token, "/folders").post(body).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            parseFolder(JSONObject(response.body?.string() ?: return null))
        }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "Crear carpeta falló: ${e.message}", e)
        null
    }
}

fun fetchFolderManifest(serverUrl: String, token: String, folderId: Int): List<CloudPhoto>? {
    return try {
        val request = authed(serverUrl, token, "/folders/$folderId/manifest").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val arr = JSONObject(response.body?.string() ?: return null).getJSONArray("photos")
            (0 until arr.length()).map {
                val p = arr.getJSONObject(it)
                CloudPhoto(p.getInt("id"), p.getString("hash_sha256"), p.getString("filename"))
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "Manifest de carpeta falló: ${e.message}", e)
        null
    }
}

/**
 * POST /folders/{id}/link: mete en la carpeta las fotos que el server ya
 * tiene por hash (subidas desde otra carpeta u otro dispositivo), sin
 * reenviar los bytes. Devuelve (enlazadas, hashes que sí hay que subir).
 */
private fun linkHashes(
    serverUrl: String,
    token: String,
    folderId: Int,
    hashes: Collection<String>
): Pair<Int, Set<String>>? {
    return try {
        val body = JSONObject().put("hashes", JSONArray(hashes)).toString().toRequestBody(JSON_TYPE)
        val request = authed(serverUrl, token, "/folders/$folderId/link").post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string() ?: return null)
            val missing = json.getJSONArray("missing")
            json.getInt("linked") to (0 until missing.length()).map { missing.getString(it) }.toSet()
        }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "Link de hashes falló: ${e.message}", e)
        null
    }
}

private fun parseFolder(json: JSONObject) = CloudFolder(
    id = json.getInt("id"),
    name = json.getString("name"),
    photoCount = json.getInt("photo_count"),
    totalSizeBytes = json.getLong("total_size_bytes")
)

// --- Carpeta local ---

/** Nombre legible de una carpeta SAF (p.ej. "Camera"). */
fun localFolderName(context: Context, treeUri: Uri): String =
    DocumentFile.fromTreeUri(context, treeUri)?.name
        ?: treeUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        ?: "Carpeta"

/**
 * Lista las imágenes de la carpeta con su hash (usando la caché de Room).
 * Null si la carpeta ya no es accesible (borrada o permiso revocado).
 */
suspend fun scanLocalFolder(context: Context, treeUri: Uri): List<LocalPhoto>? {
    val db = AppDatabase.getInstance(context)
    val hashDao = db.localFileHashDao()
    val images = try {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!folder.exists() || !folder.canRead()) return null
        folder.listFiles().filter { it.isFile && (it.type?.startsWith("image/") == true) }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "No se pudo leer la carpeta $treeUri: ${e.message}", e)
        return null
    }

    // Archivos ocultos: la papelera de Android (".trashed-<caducidad>-IMG.jpg",
    // fotos borradas desde la galería que se guardan ~30 días) y los
    // ".pending-..." de fotos a medio guardar. La galería no los enseña,
    // pero SAF sí. Si una versión anterior los encoló, se cancelan.
    val (hidden, files) = images.partition { it.name?.startsWith(".") == true }
    if (hidden.isNotEmpty()) {
        val workManager = WorkManager.getInstance(context)
        hidden.forEach {
            workManager.cancelUniqueWork(it.uri.toString())
            db.photoSyncDao().deleteByUri(it.uri.toString())
        }
    }

    return files.mapNotNull { doc ->
        val uriString = doc.uri.toString()
        val size = doc.length()
        val modified = doc.lastModified()
        val cached = hashDao.getByUri(uriString)
        val hash = if (cached != null && cached.size == size && cached.lastModified == modified) {
            cached.hash
        } else {
            computeHash(context, doc.uri)?.also {
                hashDao.upsert(LocalFileHash(uriString, size, modified, it))
            }
        }
        hash?.let { LocalPhoto(doc, it) }
    }
}

/** Compara local vs. nube por hash. */
suspend fun computeFolderStatus(
    context: Context,
    serverUrl: String,
    token: String,
    folderId: Int,
    treeUri: Uri
): FolderStatus {
    val local = scanLocalFolder(context, treeUri)
        ?: return FolderStatus.Error("No se puede leer la carpeta local. Vuelve a vincularla.")
    val cloud = fetchFolderManifest(serverUrl, token, folderId)
        ?: return FolderStatus.Error("No se pudo consultar el server")

    val localHashes = local.map { it.hash }.toSet()
    val cloudHashes = cloud.map { it.hash }.toSet()
    return FolderStatus.Ready(
        toUpload = (localHashes - cloudHashes).size,
        toDownload = (cloudHashes - localHashes).size
    )
}

// --- Subir / bajar (aditivos: nunca borran nada en ningún lado) ---

/** Local -> server: sube lo que la carpeta de la nube no tiene. */
suspend fun pushFolder(
    context: Context,
    serverUrl: String,
    token: String,
    folderId: Int,
    treeUri: Uri
): String {
    val local = scanLocalFolder(context, treeUri) ?: return "No se puede leer la carpeta local"
    val cloud = fetchFolderManifest(serverUrl, token, folderId)
        ?: return "No se pudo consultar el server"

    val cloudHashes = cloud.map { it.hash }.toSet()
    val missing = local.filter { it.hash !in cloudHashes }.distinctBy { it.hash }
    if (missing.isEmpty()) return "Nada que subir: la nube ya tiene todo"

    // Primero enlazamos lo que el server ya tiene (otra carpeta / otro móvil).
    val (linked, needUpload) = linkHashes(serverUrl, token, folderId, missing.map { it.hash })
        ?: return "No se pudo consultar el server"

    val dao = AppDatabase.getInstance(context).photoSyncDao()
    val workManager = WorkManager.getInstance(context)
    val deviceId = "${Build.MANUFACTURER} ${Build.MODEL}"
    var enqueued = 0

    for (photo in missing.filter { it.hash in needUpload }) {
        val uriString = photo.doc.uri.toString()
        dao.upsert(PhotoSyncState(localUri = uriString, hash = photo.hash, status = "pending"))

        val inputData = workDataOf(
            UploadWorker.KEY_URI to uriString,
            UploadWorker.KEY_SERVER_URL to serverUrl,
            UploadWorker.KEY_FILENAME to (photo.doc.name ?: "photo.jpg"),
            UploadWorker.KEY_MIME_TYPE to (photo.doc.type ?: "image/jpeg"),
            UploadWorker.KEY_DEVICE_ID to deviceId,
            UploadWorker.KEY_HASH to photo.hash,
            UploadWorker.KEY_TAKEN_AT to extractTakenAt(context, photo.doc),
            UploadWorker.KEY_FOLDER_ID to folderId
        )

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(UploadWorker.TAG)
            .build()

        // REPLACE: si ya había un worker para esta foto (de un sync anterior,
        // terminado o fallido), lo sustituye.
        workManager.enqueueUniqueWork(uriString, ExistingWorkPolicy.REPLACE, request)
        enqueued++
    }

    val parts = mutableListOf("Subiendo: $enqueued")
    if (linked > 0) parts.add("Ya estaban en el server (enlazadas sin resubir): $linked")
    return parts.joinToString(" | ")
}

/** Server -> local: baja a la carpeta local lo que esta no tiene. */
suspend fun pullFolder(
    context: Context,
    serverUrl: String,
    token: String,
    folderId: Int,
    treeUri: Uri
): String {
    val local = scanLocalFolder(context, treeUri) ?: return "No se puede leer la carpeta local"
    val cloud = fetchFolderManifest(serverUrl, token, folderId)
        ?: return "No se pudo consultar el server"

    val localHashes = local.map { it.hash }.toSet()
    val missing = cloud.filter { it.hash !in localHashes }.distinctBy { it.hash }
    if (missing.isEmpty()) return "Nada que bajar: la carpeta local ya tiene todo"

    val workManager = WorkManager.getInstance(context)
    for (photo in missing) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_SERVER_URL to serverUrl,
                    DownloadWorker.KEY_PHOTO_ID to photo.id,
                    DownloadWorker.KEY_TREE_URI to treeUri.toString(),
                    DownloadWorker.KEY_FILENAME to photo.filename
                )
            )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .build()

        // KEEP: si se pulsa "Bajar" dos veces mientras sigue descargando,
        // no se duplica el archivo. (Si el trabajo anterior ya terminó, se
        // vuelve a encolar con normalidad.)
        workManager.enqueueUniqueWork("download|$treeUri|${photo.hash}", ExistingWorkPolicy.KEEP, request)
    }
    return "Bajando: ${missing.size} (pulsa Actualizar cuando terminen)"
}

private fun networkConstraints() =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

// --- Hash y fecha de la foto ---

private fun computeHash(context: Context, uri: Uri): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Determina la fecha real de la foto, en orden de preferencia:
 *  1. EXIF DateTimeOriginal (la escribió la cámara al disparar)
 *  2. EXIF DateTime
 *  3. Fecha de modificación del archivo (respaldo: muchas fotos llegan
 *     sin EXIF —descargadas, de WhatsApp, editadas, capturas— y en esos
 *     casos la fecha del archivo suele ser la más cercana a la real)
 * Devuelve null solo si no hay ninguna de las tres.
 */
private fun extractTakenAt(context: Context, doc: DocumentFile): String? {
    val uri = doc.uri

    val exifDate = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        }
    } catch (e: Exception) {
        null
    }

    if (exifDate != null) {
        val iso = convertExifDateToIso(exifDate)
        if (iso != null) {
            android.util.Log.d("OffCloud", "${doc.name}: fecha EXIF = $iso")
            return iso
        }
    }

    val modified = doc.lastModified()
    if (modified > 0) {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(modified))
        android.util.Log.d("OffCloud", "${doc.name}: sin EXIF, usando fecha de archivo = $iso")
        return iso
    }

    android.util.Log.d("OffCloud", "${doc.name}: sin fecha disponible")
    return null
}

/** Convierte "yyyy:MM:dd HH:mm:ss" (formato EXIF) a ISO 8601. */
private fun convertExifDateToIso(exifDate: String): String? {
    return try {
        val parts = exifDate.trim().split(" ")
        if (parts.size != 2) return null
        val datePart = parts[0].replace(":", "-")
        "${datePart}T${parts[1]}"
    } catch (e: Exception) {
        null
    }
}
