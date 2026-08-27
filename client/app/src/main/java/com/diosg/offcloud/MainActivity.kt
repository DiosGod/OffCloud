package com.diosg.offcloud

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// --- Paleta OffCloud: negro/morado ---
private val BgDark = Color(0xFF0D0B14)
private val SurfaceElevated = Color(0xFF1A1625)
private val AccentPrimary = Color(0xFFA855F7)
private val AccentSecondary = Color(0xFFC4B5FD)
private val TextPrimary = Color(0xFFF5F3FA)
private val TextMuted = Color(0xFF9B93AC)
private val ErrorColor = Color(0xFFF87171)

private val OffCloudBackground = Brush.linearGradient(
    colors = listOf(Color(0xFF241340), Color(0xFF0D0B14), Color(0xFF160B24))
)

private val DisplayFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-black")))
private val BodyFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-light")))

private val OffCloudColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    onSecondary = BgDark,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceElevated,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    error = ErrorColor,
    onError = Color.White
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("offcloud_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = OffCloudColorScheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OffCloudBackground),
                    contentAlignment = Alignment.Center
                ) {
                    AppRoot(prefs)
                }
            }
        }
    }
}

@Composable
fun AppRoot(prefs: SharedPreferences) {
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }

    if (token.isBlank()) {
        LoginScreen(
            initialServerUrl = serverUrl,
            onLoginSuccess = { url, newToken ->
                serverUrl = url
                token = newToken
                prefs.edit()
                    .putString("server_url", url)
                    .putString("token", newToken)
                    .apply()
            }
        )
    } else {
        SyncScreen(
            prefs = prefs,
            serverUrl = serverUrl,
            onLogout = {
                token = ""
                prefs.edit().remove("token").apply()
            }
        )
    }
}

@Composable
private fun CenteredCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .padding(24.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
fun LoginScreen(
    initialServerUrl: String,
    onLoginSuccess: (serverUrl: String, token: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }

    CenteredCard {
        Text(
            "OffCloud",
            fontSize = 32.sp,
            fontFamily = DisplayFont,
            letterSpacing = 1.sp,
            color = AccentSecondary
        )
        Text(
            "Inicia sesión para sincronizar",
            fontSize = 14.sp,
            fontFamily = BodyFont,
            color = TextMuted
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("URL del server") },
            placeholder = { Text("http://100.x.x.x:8000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
                    errorText = "Rellena todos los campos"
                    return@Button
                }
                val cleanUrl = serverUrl.trimEnd('/')
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    errorText = "La URL debe empezar con http:// o https://"
                    return@Button
                }
                isLoggingIn = true
                errorText = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        login(cleanUrl, username, password)
                    }
                    isLoggingIn = false
                    if (result != null) {
                        onLoginSuccess(cleanUrl, result)
                    } else {
                        errorText = "Login fallido: revisa URL, usuario o contraseña"
                    }
                }
            },
            enabled = !isLoggingIn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoggingIn) "Entrando..." else "Iniciar sesión")
        }

        if (errorText.isNotBlank()) {
            Text(errorText, color = ErrorColor, fontSize = 13.sp)
        }
    }
}

/** Llama a POST /auth/login. Devuelve el token si es correcto, null si falla. */
private fun login(serverUrl: String, username: String, password: String): String? {
    return try {
        val client = OkHttpClient()
        val formBody = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url("$serverUrl/auth/login")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            JSONObject(bodyStr).optString("token").ifBlank { null }
        }
    } catch (e: Exception) {
        android.util.Log.e("OffCloud", "Login falló: ${e.javaClass.simpleName} - ${e.message}", e)
        null
    }
}

@Composable
fun SyncScreen(prefs: SharedPreferences, serverUrl: String, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).photoSyncDao() }

    var folderUri by remember {
        mutableStateOf(prefs.getString("folder_uri", null)?.let { Uri.parse(it) })
    }
    var statusText by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }

    // Se actualiza solo en tiempo real a medida que WorkManager va marcando
    // fotos como "uploaded" o "error" en la base de datos.
    val syncStates by dao.getAllFlow().collectAsState(initial = emptyList())
    val uploadedCount = syncStates.count { it.status == "uploaded" }
    val pendingCount = syncStates.count { it.status == "pending" }
    val errorCount = syncStates.count { it.status == "error" }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            folderUri = uri
            prefs.edit().putString("folder_uri", uri.toString()).apply()
        }
    }

    CenteredCard {
        Text(
            "OffCloud",
            fontSize = 32.sp,
            fontFamily = DisplayFont,
            letterSpacing = 1.sp,
            color = AccentSecondary
        )
        Text("Conectado a $serverUrl", fontSize = 12.sp, fontFamily = BodyFont, color = TextMuted)

        Button(
            onClick = { folderPickerLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Elegir carpeta de fotos")
        }

        Text(
            text = folderUri?.let { "Carpeta: ${it.lastPathSegment}" }
                ?: "Ninguna carpeta seleccionada",
            fontSize = 13.sp,
            color = TextMuted
        )

        Button(
            onClick = {
                val uri = folderUri
                if (uri == null) {
                    statusText = "Elige una carpeta primero"
                    return@Button
                }
                isScanning = true
                statusText = "Escaneando carpeta..."
                scope.launch {
                    val token = prefs.getString("token", "") ?: ""
                    val result = withContext(Dispatchers.IO) {
                        scanAndEnqueue(context, uri, serverUrl, token, dao)
                    }
                    statusText = result
                    isScanning = false
                }
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Escaneando..." else "Sincronizar")
        }

        if (statusText.isNotBlank()) {
            Text(statusText, fontSize = 13.sp, color = TextPrimary)
        }

        // Contador en vivo: se actualiza solo aunque la app siga abierta
        // mientras WorkManager sube las fotos en segundo plano.
        Text(
            "Subidas: $uploadedCount · Pendientes: $pendingCount · Errores: $errorCount",
            fontSize = 12.sp,
            color = TextMuted
        )

        TextButton(onClick = onLogout) {
            Text("Cerrar sesión", color = AccentSecondary)
        }
    }
}

/**
 * Fase 3: escanea la carpeta, calcula el hash de cada foto, y solo encola
 * en WorkManager las que NO estén ya marcadas como "uploaded" con ese
 * mismo hash en Room. Evita resubir todo en cada sync.
 */
private suspend fun scanAndEnqueue(
    context: Context,
    folderUri: Uri,
    serverUrl: String,
    token: String,
    dao: PhotoSyncDao
): String {
    val folder = DocumentFile.fromTreeUri(context, folderUri)
        ?: return "No se pudo abrir la carpeta"

    val imageFiles = folder.listFiles().filter {
        it.isFile && (it.type?.startsWith("image/") == true)
    }

    if (imageFiles.isEmpty()) {
        return "No se encontraron imágenes en la carpeta"
    }

    val workManager = WorkManager.getInstance(context)
    var enqueued = 0
    var skipped = 0

    for (doc in imageFiles) {
        val uriString = doc.uri.toString()
        val hash = computeHash(context, doc.uri) ?: continue

        val existing = dao.getByUri(uriString)
        if (existing != null && existing.status == "uploaded" && existing.hash == hash) {
            skipped++
            continue
        }

        dao.upsert(PhotoSyncState(localUri = uriString, hash = hash, status = "pending"))

        val inputData = workDataOf(
            UploadWorker.KEY_URI to uriString,
            UploadWorker.KEY_SERVER_URL to serverUrl,
            UploadWorker.KEY_TOKEN to token,
            UploadWorker.KEY_FILENAME to (doc.name ?: "photo.jpg"),
            UploadWorker.KEY_MIME_TYPE to (doc.type ?: "image/jpeg"),
            UploadWorker.KEY_DEVICE_ID to "android-client",
            UploadWorker.KEY_HASH to hash
        )

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag(UploadWorker.TAG)
            .build()

        // ExistingWorkPolicy.KEEP: si ya hay un worker encolado para esta
        // misma foto (ej. sync anterior aún reintentando), no lo duplica.
        workManager.enqueueUniqueWork(uriString, ExistingWorkPolicy.KEEP, request)
        enqueued++
    }

    return "Encoladas para subir: $enqueued | Ya al día: $skipped"
}

private fun computeHash(context: Context, uri: Uri): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}
