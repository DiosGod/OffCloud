# Setup completo — OffCloud Server (Windows)

Guía paso a paso para levantar el server desde cero en un PC nuevo,
incluyendo la configuración de red que costó encontrar la primera vez.

## 1. Requisitos previos

- Python 3.12+ instalado (`py --version` para confirmar)
- El repo clonado/copiado en el PC, carpeta `host/`

## 2. Entorno Python

```cmd
cd C:\ruta\a\OffCloud\host
py -m venv venv
venv\Scripts\activate.bat
pip install -r requirements.txt
```

## 3. Configurar `.env`

```cmd
copy .env.example .env
```

Edita `.env` con un editor de texto y define:

```
AUTH_USERNAME=tu_usuario
AUTH_PASSWORD=una_contraseña_fuerte
API_TOKEN=genera_uno_con_el_comando_de_abajo
STORAGE_DIR=./data/photos
DB_PATH=./data/app.db
```

Para generar un `API_TOKEN` seguro (64 caracteres random):

```cmd
python -c "import secrets; print(secrets.token_hex(32))"
```

**Importante**: revisa que el `.env` no tenga ninguna línea de más
(clave que no sea una de las 5 de arriba) — aunque el código ya la
ignora, es más limpio no dejarla.

## 4. Instalar y configurar Tailscale

1. Crear cuenta en [tailscale.com](https://tailscale.com) (gratis para uso personal).
2. Instalar Tailscale en el PC/NAS y hacer login.
3. Instalar la app Tailscale en el móvil (Google Play / App Store) y hacer login con la misma cuenta.
4. Anotar la IP de Tailscale del PC:
   ```cmd
   tailscale ip -4
   ```
   (algo tipo `100.x.x.x` — la usarás en la app Android)

## 5. Firewall de Windows — la parte que da problemas

Por defecto, Windows bloquea conexiones **entrantes** de otros dispositivos
aunque el propio PC pueda conectarse a sí mismo sin problema. Hay que:

### 5.1. Crear una regla que permita el puerto 8000

1. Buscar "Firewall de Windows Defender" → **Configuración avanzada**.
2. **Reglas de entrada** → **Nueva regla...**
3. Tipo: **Puerto** → Siguiente.
4. **TCP**, puerto local específico: `8000` → Siguiente.
5. **Permitir la conexión** → Siguiente.
6. Marcar los 3 perfiles (Dominio, Privado, Público) → Siguiente.
7. Nombre: `OffCloud Server` → Finalizar.

### 5.2. Restringir esa regla solo a la red de Tailscale (recomendado)

Sin este paso, cualquier dispositivo en tu misma red WiFi (no solo los
tuyos vía Tailscale) podría intentar tocar el puerto 8000.

1. Doble click en la regla `OffCloud Server` → pestaña **Ámbito**.
2. En "Dirección IP remota", elegir **Estas direcciones IP**.
3. Agregar: `100.64.0.0/10` (rango completo que usa Tailscale).
4. Aceptar.

Con esto, solo tráfico que venga por el túnel de Tailscale puede
siquiera intentar conectar al puerto — cualquier otro origen es
rechazado antes de llegar al server.

### 5.3. Revisar reglas de bloqueo automáticas para python.exe

Windows a veces crea reglas de **bloqueo** automáticas para `python.exe`
la primera vez que corre un server (si en algún punto respondiste "No
permitir" a un popup de red, o Windows lo hizo por su cuenta). Una regla
de bloqueo siempre gana sobre una de permiso, así que si el punto 5.1
no funciona, revisa esto:

1. **Reglas de entrada** en el firewall avanzado.
2. Busca cualquier entrada para `python.exe` o `uvicorn` con ícono de
   prohibido (círculo rojo) que **no** sea la regla que creaste tú.
3. Deshabilítala (click derecho → Deshabilitar) o elimínala.

### 5.4. Diagnóstico si algo falla

Para saber si el problema es el firewall o algo más:

```cmd
:: Desde el propio PC, probar la IP de Tailscale (no 127.0.0.1)
curl http://100.x.x.x:8000/health
```

Si esto funciona pero desde el móvil no, el problema es casi siempre
el firewall bloqueando tráfico **entrante** (el PC puede alcanzarse a
sí mismo sin pasar por esa capa, pero el tráfico externo sí la cruza).

Como prueba temporal (nunca dejarlo así), desactivar el firewall de los
3 perfiles y probar desde el móvil — si ahí sí funciona, confirma que
es el firewall, y hay que revisar 5.1/5.3 con más cuidado. Reactivar el
firewall inmediatamente después de probar.

## 6. Arrancar el server

Doble click en `start_server.bat` (dentro de `host/`), o manualmente:

```cmd
venv\Scripts\activate.bat
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 7. Verificar que todo funciona de punta a punta

Desde el propio PC:
```cmd
curl http://100.x.x.x:8000/health
```

Desde el móvil (con **datos móviles**, no WiFi de casa, para probar
acceso remoto real), en el navegador:
```
http://100.x.x.x:8000/health
```

Ambos deberían devolver `{"status":"ok"}`.

## 8. Configurar la app Android

En la pantalla de login de la app, usar:
- **URL del server**: `http://100.x.x.x:8000` (con el `http://`, si no
  la app crashea al construir la URL)
- **Usuario / contraseña**: los mismos de `AUTH_USERNAME` / `AUTH_PASSWORD`
  en el `.env` del server

## 9. Fase 3: Room + WorkManager (KSP)

Para tu proyecto (Kotlin 2.2.10), la versión de KSP compatible es
**2.2.10-2.0.2**.

### `build.gradle.kts` de nivel raíz:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
```

### `app/build.gradle.kts`:
```kotlin
plugins {
    id("com.google.devtools.ksp")
    // ... tus otros plugins existentes
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // ... OkHttp y DocumentFile que ya tenías
}
```

Sync Gradle después de estos cambios.

### Si el sync falla con "Using kotlin.sourceSets DSL... not allowed with built-in Kotlin"

Es un conflicto conocido entre KSP y las versiones nuevas de Android
Gradle Plugin. Fix: en `gradle.properties` (raíz del proyecto), añade:

```
android.disallowKotlinSourceSets=false
```

Y vuelve a sincronizar.

### Si KSP falla con "unexpected jvm signature V"

Bug conocido de KSP2 procesando funciones `suspend` que devuelven `Unit`
en el DAO de Room (como nuestro `upsert`). Fix: usar Room **2.7.1 o
superior** en vez de 2.6.1 (las 3 líneas: `room-runtime`, `room-ktx`,
`room-compiler`).

- [ ] Python + venv + `pip install -r requirements.txt`
- [ ] `.env` configurado con token generado con `secrets.token_hex(32)`
- [ ] Tailscale instalado y conectado (PC + móvil)
- [ ] Regla de firewall TCP 8000 creada, restringida a `100.64.0.0/10`
- [ ] Sin reglas de bloqueo residuales para python.exe
- [ ] `curl http://100.x.x.x:8000/health` funciona desde el propio PC
- [ ] `http://100.x.x.x:8000/health` funciona desde el móvil con datos móviles
- [ ] App Android configurada con la URL correcta (con `http://`)
