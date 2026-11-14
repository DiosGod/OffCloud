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
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// --- Paleta OffCloud: negro/morado ---
internal val BgDark = Color(0xFF0D0B14)
internal val SurfaceElevated = Color(0xFF1A1625)
internal val AccentPrimary = Color(0xFFA855F7)
internal val AccentSecondary = Color(0xFFC4B5FD)
internal val TextPrimary = Color(0xFFF5F3FA)
internal val TextMuted = Color(0xFF9B93AC)
internal val ErrorColor = Color(0xFFF87171)

private val OffCloudBackground = Brush.linearGradient(
    colors = listOf(Color(0xFF241340), Color(0xFF0D0B14), Color(0xFF160B24))
)

internal val DisplayFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-black")))
internal val BodyFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-light")))

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
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Wallpaper de fondo
                    Image(
                        painter = painterResource(id = R.drawable.background),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Velo oscuro encima: mantiene el texto legible sobre
                    // las zonas más claras del wallpaper. Más suave que
                    // antes para que se note la tarjeta translúcida.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x990D0B14))
                    )
                    AppRoot(prefs)
                }
            }
        }
    }
}

@Composable
fun AppRoot(prefs: SharedPreferences) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    // El token va cifrado con el Keystore (ver SessionStore).
    var token by remember { mutableStateOf(SessionStore.getToken(context) ?: "") }

    // username vacío = sesión de una versión anterior de la app (no lo
    // guardaba); hace falta para saber qué carpetas están vinculadas.
    if (token.isBlank() || username.isBlank()) {
        LoginScreen(
            initialServerUrl = serverUrl,
            onLoginSuccess = { url, user, newToken ->
                serverUrl = url
                username = user
                token = newToken
                prefs.edit()
                    .putString("server_url", url)
                    .putString("username", user)
                    .apply()
                SessionStore.setToken(context, newToken)
            }
        )
    } else {
        FoldersScreen(
            prefs = prefs,
            serverUrl = serverUrl,
            username = username,
            token = token,
            onLogout = {
                token = ""
                SessionStore.clearToken(context)
            }
        )
    }
}

// Bloques dentro de la tarjeta: borde fino + relleno blanco muy sutil, para
// que se distingan de la tarjeta base sin competir con ella.
private val PanelShape = RoundedCornerShape(16.dp)
private val PanelFill = Color(0x0FFFFFFF)
private val PanelBorder = Color(0x4DC4B5FD)

internal fun Modifier.innerPanel(borderColor: Color = PanelBorder, fill: Color = PanelFill): Modifier =
    this
        .fillMaxWidth()
        .border(1.dp, borderColor, PanelShape)
        .background(fill, PanelShape)
        .padding(14.dp)

/**
 * Tarjeta base translúcida (efecto cristal). Sin sombra: con un fondo
 * translúcido la sombra se ve a través de la tarjeta y la ensucia.
 */
@Composable
private fun CenteredCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .padding(24.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x8C1A1625)),
        border = BorderStroke(1.dp, Color(0x26FFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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

/**
 * Server por defecto: nombre MagicDNS de Tailscale del PC servidor (no su
 * IP, que puede cambiar). Requiere que la máquina se llame "offcloud" en el
 * panel de Tailscale.
 */
private const val DEFAULT_SERVER_URL = "http://offcloud:8000"

@Composable
fun LoginScreen(
    initialServerUrl: String,
    onLoginSuccess: (serverUrl: String, username: String, token: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(initialServerUrl.ifBlank { DEFAULT_SERVER_URL }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    // El campo de URL solo se enseña si el usuario lo pide: lo normal es
    // que el server se encuentre solo por su nombre de Tailscale.
    var showServerField by remember { mutableStateOf(false) }

    // Si la URL guardada ya no responde (p.ej. una IP antigua) pero el
    // nombre por defecto sí, se cambia sola.
    LaunchedEffect(Unit) {
        if (serverUrl == DEFAULT_SERVER_URL) return@LaunchedEffect
        val savedWorks = withContext(Dispatchers.IO) { pingServer(serverUrl) != null }
        if (!savedWorks && withContext(Dispatchers.IO) { pingServer(DEFAULT_SERVER_URL) != null }) {
            serverUrl = DEFAULT_SERVER_URL
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
        Text(
            "Inicia sesión para sincronizar",
            fontSize = 14.sp,
            fontFamily = BodyFont,
            color = TextMuted
        )

        // Solo se comprueba el server cuando la URL tiene pinta de válida.
        val cleanUrl = serverUrl.trim().trimEnd('/')
        ConnectionBanner(
            serverUrl = cleanUrl.takeIf {
                (it.startsWith("http://") || it.startsWith("https://")) && it.length > "https://".length
            }
        )

        if (showServerField) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL del server") },
                placeholder = { Text(DEFAULT_SERVER_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Server: ${cleanUrl.substringAfter("://")}",
                    fontSize = 13.sp,
                    color = TextMuted
                )
                TextButton(onClick = { showServerField = true }) {
                    Text("Cambiar", color = AccentSecondary, fontSize = 12.sp)
                }
            }
        }

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
                        login(cleanUrl, username.trim(), password)
                    }
                    isLoggingIn = false
                    if (result != null) {
                        onLoginSuccess(cleanUrl, username.trim(), result)
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

/** Valor centinela de [FoldersScreen]: el selector de carpeta es para crear una carpeta nueva. */
private const val NEW_FOLDER = -1

/**
 * Pantalla principal: carpetas que la cuenta tiene en la nube y, para cada
 * una, si en ESTE dispositivo está vinculada a una carpeta local y al día.
 */
@Composable
fun FoldersScreen(
    prefs: SharedPreferences,
    serverUrl: String,
    username: String,
    token: String,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).photoSyncDao() }
    val links = remember(serverUrl, username) { FolderLinks(prefs, serverUrl, username) }

    var folders by remember { mutableStateOf<List<CloudFolder>?>(null) }
    var linked by remember { mutableStateOf(links.all()) }
    val statuses = remember { mutableStateMapOf<Int, FolderStatus>() }
    var busyFolderId by remember { mutableStateOf<Int?>(null) }
    var statusText by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    // Pestaña abierta: id de carpeta, o NEW_FOLDER para "+ Añadir". Por id y
    // no por índice, para que no salte de carpeta al recargar la lista.
    var selectedTab by remember { mutableStateOf<Int?>(null) }

    val syncStates by dao.getAllFlow().collectAsState(initial = emptyList())
    val uploadedCount = syncStates.count { it.status == "uploaded" }
    val pendingCount = syncStates.count { it.status == "pending" }
    val errorCount = syncStates.count { it.status == "error" }

    suspend fun refreshStatus(folderId: Int) {
        val uri = links.all()[folderId]
        if (uri == null) {
            statuses[folderId] = FolderStatus.Unlinked
            return
        }
        statuses[folderId] = FolderStatus.Checking
        statuses[folderId] = withContext(Dispatchers.IO) {
            computeFolderStatus(context, serverUrl, token, folderId, uri)
        }
    }

    // Carga la lista de la nube y comprueba cada carpeta vinculada.
    LaunchedEffect(refreshKey) {
        val result = withContext(Dispatchers.IO) { fetchFolders(serverUrl, token) }
        loadError = result == null
        if (result == null) return@LaunchedEffect
        folders = result
        linked = links.all()
        result.forEach { refreshStatus(it.id) }
    }

    // Cuando terminan las subidas en segundo plano, vuelve a comprobar.
    var hadPending by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCount) {
        if (pendingCount > 0) {
            hadPending = true
        } else if (hadPending) {
            hadPending = false
            refreshKey++
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val target = pickingFor
        pickingFor = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        // Lectura para subir, escritura para poder bajar fotos de la nube.
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        scope.launch {
            val folderId = if (target == NEW_FOLDER) {
                val name = localFolderName(context, uri)
                val created = withContext(Dispatchers.IO) { createFolder(serverUrl, token, name) }
                if (created == null) {
                    statusText = "No se pudo crear la carpeta en el server"
                    return@launch
                }
                statusText = "Carpeta \"${created.name}\" vinculada"
                created.id
            } else {
                statusText = "Carpeta vinculada a ${localFolderName(context, uri)}"
                target
            }
            links.set(folderId, uri)
            selectedTab = folderId
            refreshKey++
        }
    }

    fun runAction(folderId: Int, action: suspend (Uri) -> String) {
        val uri = linked[folderId] ?: return
        busyFolderId = folderId
        scope.launch {
            statusText = withContext(Dispatchers.IO) { action(uri) }
            refreshStatus(folderId)
            busyFolderId = null
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
        Text("$username · $serverUrl", fontSize = 12.sp, fontFamily = BodyFont, color = TextMuted)

        // Si el server vuelve tras estar caído, recarga carpetas y estados.
        ConnectionBanner(serverUrl = serverUrl, onServerBack = { refreshKey++ })

        val currentFolders = folders
        when {
            loadError -> Text(
                "No se pudo cargar la lista del server. Si persiste, cierra sesión y vuelve a entrar.",
                fontSize = 13.sp,
                color = ErrorColor
            )
            currentFolders == null -> Text("Cargando...", fontSize = 13.sp, color = TextMuted)
        }

        if (currentFolders != null) {
            // Sin carpetas, o con la pestaña elegida ya inexistente, se abre la primera.
            val tabIds = currentFolders.map { it.id } + NEW_FOLDER
            val activeTab = selectedTab?.takeIf { it in tabIds } ?: tabIds.first()

            FolderTabs(
                folders = currentFolders,
                statuses = statuses,
                selected = activeTab,
                onSelect = { selectedTab = it }
            )

            val folder = currentFolders.firstOrNull { it.id == activeTab }
            if (folder == null) {
                AddFolderPanel(
                    isFirst = currentFolders.isEmpty(),
                    onAdd = {
                        pickingFor = NEW_FOLDER
                        folderPickerLauncher.launch(null)
                    }
                )
            } else {
                FolderPanel(
                    folder = folder,
                    localName = linked[folder.id]?.let { localFolderName(context, it) },
                    status = statuses[folder.id] ?: FolderStatus.Checking,
                    busy = busyFolderId == folder.id,
                    onLink = {
                        pickingFor = folder.id
                        folderPickerLauncher.launch(null)
                    },
                    onUnlink = {
                        links.remove(folder.id)
                        linked = links.all()
                        statuses[folder.id] = FolderStatus.Unlinked
                    },
                    onPush = {
                        runAction(folder.id) { uri -> pushFolder(context, serverUrl, token, folder.id, uri) }
                    },
                    onPull = {
                        runAction(folder.id) { uri -> pullFolder(context, serverUrl, token, folder.id, uri) }
                    }
                )
            }
        }

        if (statusText.isNotBlank()) {
            Text(statusText, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.fillMaxWidth())
        }

        // Contador en vivo: se actualiza solo aunque la app siga abierta
        // mientras WorkManager sube las fotos en segundo plano.
        Text(
            "Subidas: $uploadedCount · Pendientes: $pendingCount · Errores: $errorCount",
            fontSize = 12.sp,
            color = TextMuted
        )

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { refreshKey++ }) {
                Text("Actualizar", color = AccentSecondary, fontSize = 13.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { dao.deleteAll() }
                    statusText = "Contadores borrados"
                }
            }) {
                Text("Limpiar contadores", color = TextMuted, fontSize = 12.sp)
            }
        }

        TextButton(onClick = onLogout) {
            Text("Cerrar sesión", color = AccentSecondary)
        }
    }
}

/**
 * Una pestaña por carpeta de la nube (se desplaza en horizontal si hay
 * muchas) más "+ Añadir". El punto delante del nombre resume el estado sin
 * tener que abrir cada pestaña.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTabs(
    folders: List<CloudFolder>,
    statuses: Map<Int, FolderStatus>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val tabIds = folders.map { it.id } + NEW_FOLDER
    PrimaryScrollableTabRow(
        selectedTabIndex = tabIds.indexOf(selected),
        containerColor = Color.Transparent,
        contentColor = AccentSecondary,
        edgePadding = 0.dp,
        divider = {}
    ) {
        folders.forEach { folder ->
            val (mark, markColor) = statusMark(statuses[folder.id])
            Tab(
                selected = folder.id == selected,
                onClick = { onSelect(folder.id) },
                selectedContentColor = TextPrimary,
                unselectedContentColor = TextMuted,
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mark, color = markColor, fontSize = 12.sp)
                        Text(folder.name, fontSize = 14.sp, maxLines = 1)
                    }
                }
            )
        }
        Tab(
            selected = selected == NEW_FOLDER,
            onClick = { onSelect(NEW_FOLDER) },
            selectedContentColor = TextPrimary,
            unselectedContentColor = AccentSecondary,
            text = { Text("+ Añadir", fontSize = 14.sp) }
        )
    }
}

/** Marca corta de estado para la pestaña: ✓ al día, ● hay cambios, ! error, ○ sin vincular. */
private fun statusMark(status: FolderStatus?): Pair<String, Color> = when (status) {
    is FolderStatus.Ready ->
        if (status.upToDate) "✓" to AccentSecondary else "●" to AccentPrimary
    is FolderStatus.Error -> "!" to ErrorColor
    FolderStatus.Unlinked -> "○" to TextMuted
    else -> "…" to TextMuted
}

@Composable
private fun AddFolderPanel(isFirst: Boolean, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.innerPanel(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (isFirst) {
                "Aún no hay carpetas en la nube. Añade una carpeta de este móvil para empezar."
            } else {
                "Elige una carpeta del móvil. Si en la nube ya hay una con el mismo nombre, " +
                    "se vinculan; si no, se crea."
            },
            fontSize = 13.sp,
            color = TextPrimary
        )
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Elegir carpeta del móvil")
        }
    }
}

@Composable
private fun FolderPanel(
    folder: CloudFolder,
    localName: String?,
    status: FolderStatus,
    busy: Boolean,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit
) {
    Column(
        modifier = Modifier.innerPanel(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${folder.photoCount} fotos en la nube · ${formatSize(folder.totalSizeBytes)}",
            fontSize = 12.sp,
            color = TextMuted
        )

        if (status == FolderStatus.Unlinked) {
            Text("No vinculada en este dispositivo", fontSize = 13.sp, color = TextMuted)
            OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                Text("Vincular carpeta local", color = AccentSecondary)
            }
            return@Column
        }

        when (status) {
            is FolderStatus.Error -> Text(status.message, fontSize = 13.sp, color = ErrorColor)
            is FolderStatus.Ready -> Text(
                if (status.upToDate) {
                    "✓ Al día"
                } else {
                    "↑ ${status.toUpload} por subir · ↓ ${status.toDownload} por bajar"
                },
                fontSize = 13.sp,
                color = if (status.upToDate) AccentSecondary else TextPrimary
            )
            else -> Text("Comprobando...", fontSize = 13.sp, color = TextMuted)
        }

        Text("Local: ${localName ?: "?"}", fontSize = 12.sp, color = TextMuted)

        val ready = status as? FolderStatus.Ready
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onPush,
                enabled = !busy && (ready?.toUpload ?: 0) > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("Subir ↑")
            }
            Button(
                onClick = onPull,
                enabled = !busy && (ready?.toDownload ?: 0) > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("Bajar ↓")
            }
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onLink, enabled = !busy) {
                Text("Cambiar carpeta", color = TextMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onUnlink, enabled = !busy) {
                Text("Desvincular", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

