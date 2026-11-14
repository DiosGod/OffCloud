package com.diosg.offcloud

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

// Receptor de la app de Tailscale para automatizaciones (Tasker, Home
// Assistant...). No está documentado oficialmente: si una versión futura lo
// cambia, el botón "Conectar" cae al plan B (abrir la app de Tailscale).
private const val TAILSCALE_RECEIVER = "com.tailscale.ipn.IPNReceiver"
private const val TAILSCALE_CONNECT_ACTION = "com.tailscale.ipn.CONNECT_VPN"

private const val POLL_INTERVAL_MS = 5_000L

// Timeouts cortos: /health responde al instante; si tarda más es que no llega.
private val healthClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .build()

data class ConnectionState(
    val tailscaleInstalled: Boolean,
    val vpnActive: Boolean,
    /** Null = el server no respondió (o aún no hay URL que comprobar). */
    val serverLatencyMs: Long?
)

fun isTailscaleInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/**
 * ¿Hay alguna VPN activa? Android no dice cuál, pero en la práctica en
 * este móvil solo está Tailscale.
 */
@Suppress("DEPRECATION") // allNetworks: sigue funcionando y evita montar un NetworkCallback
fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return cm.allNetworks.any {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

/** GET /health. Devuelve la latencia en ms, o null si el server no responde. */
fun pingServer(serverUrl: String): Long? {
    return try {
        val start = System.nanoTime()
        val request = Request.Builder().url("$serverUrl/health").get().build()
        healthClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
        }
        (System.nanoTime() - start) / 1_000_000
    } catch (e: Exception) {
        null
    }
}

fun checkConnection(context: Context, serverUrl: String?): ConnectionState = ConnectionState(
    tailscaleInstalled = isTailscaleInstalled(context),
    vpnActive = isVpnActive(context),
    serverLatencyMs = serverUrl?.let { pingServer(it) }
)

/** Pide a la app de Tailscale que conecte. Devuelve false si no se pudo enviar. */
private fun requestTailscaleConnect(context: Context): Boolean = try {
    context.sendBroadcast(
        Intent(TAILSCALE_CONNECT_ACTION).setComponent(
            ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER)
        )
    )
    true
} catch (e: Exception) {
    android.util.Log.e("OffCloud", "No se pudo pedir a Tailscale que conecte: ${e.message}", e)
    false
}

private fun openTailscaleApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)?.let {
        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openTailscaleInStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
    try {
        context.startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Sin Play Store: la ficha web.
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Bloque de estado de la conexión: Tailscale instalado / conectado y si el
 * server responde. Se comprueba cada [POLL_INTERVAL_MS] mientras la app está
 * en primer plano (al volver de instalar Tailscale se ve al momento).
 *
 * @param serverUrl null si todavía no hay una URL válida (pantalla de login).
 * @param onServerBack se llama cuando el server pasa de no responder a responder.
 */
@Composable
fun ConnectionBanner(serverUrl: String?, onServerBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ConnectionState?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    LaunchedEffect(serverUrl) {
        // Pequeña espera: en el login la URL cambia con cada tecla.
        delay(600)
        val owner = context as? LifecycleOwner ?: return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val previous = state
                val next = withContext(Dispatchers.IO) { checkConnection(context, serverUrl) }
                state = next
                if (previous != null && previous.serverLatencyMs == null && next.serverLatencyMs != null) {
                    onServerBack()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    val current = state ?: return

    if (!current.tailscaleInstalled) {
        Column(
            modifier = Modifier.innerPanel(borderColor = ErrorColor, fill = Color(0x1AF87171)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Falta Tailscale", fontSize = 16.sp, fontFamily = DisplayFont, color = ErrorColor)
            Text(
                "OffCloud llega a tu server a través de Tailscale. Instálalo, inicia sesión " +
                    "con la misma cuenta que el PC y vuelve aquí.",
                fontSize = 13.sp,
                color = TextPrimary
            )
            Button(onClick = { openTailscaleInStore(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Instalar Tailscale")
            }
        }
        return
    }

    Column(
        modifier = Modifier.innerPanel(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatusLine(
            ok = current.vpnActive,
            text = if (current.vpnActive) "Tailscale conectado" else "Tailscale desconectado"
        )
        if (serverUrl != null) {
            StatusLine(
                ok = current.serverLatencyMs != null,
                text = current.serverLatencyMs?.let { "Server en línea · $it ms" } ?: "El server no responde"
            )
        }

        if (current.vpnActive && serverUrl != null && current.serverLatencyMs == null) {
            Text(
                "Tailscale está conectado pero el server no contesta: ¿está encendido el PC " +
                    "y abierto start_server.bat?",
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!current.vpnActive) {
                OutlinedButton(
                    enabled = !connecting,
                    onClick = {
                        // Un solo envío y luego esperar: repetir el broadcast
                        // puede dejar a Tailscale en un bucle conectando.
                        connecting = true
                        hint = ""
                        scope.launch {
                            val sent = requestTailscaleConnect(context)
                            var connected = false
                            var waitedSeconds = 0
                            while (sent && !connected && waitedSeconds < 8) {
                                delay(1_000)
                                waitedSeconds++
                                connected = isVpnActive(context)
                            }
                            if (connected) {
                                state = withContext(Dispatchers.IO) { checkConnection(context, serverUrl) }
                            } else {
                                hint = "No se pudo conectar automáticamente. Conecta desde Tailscale."
                                openTailscaleApp(context)
                            }
                            connecting = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (connecting) "Conectando..." else "Conectar", color = AccentSecondary)
                }
            }
            TextButton(onClick = { openTailscaleApp(context) }, modifier = Modifier.weight(1f)) {
                Text("Abrir Tailscale", color = TextMuted, fontSize = 12.sp)
            }
        }

        if (hint.isNotBlank()) {
            Text(hint, fontSize = 12.sp, color = TextMuted)
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Text(
        (if (ok) "● " else "○ ") + text,
        fontSize = 13.sp,
        color = if (ok) AccentSecondary else ErrorColor
    )
}
