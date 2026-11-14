# CLAUDE.md — Contexto del proyecto OffCloud

Backup de fotos self-hosted para reemplazar Google Fotos: app Android que
sube fotos a un server propio (PC/NAS en casa), accesible de forma privada
vía Tailscale. Repo dividido en `host/` (server) y `client/` (Android).

Última actualización: 2026-11-14.

## Decisiones de arquitectura (ya tomadas, no reabrir sin motivo)

- **Red**: Tailscale (VPN privada), nunca exposición pública a internet.
- **Tailscale en el móvil (2026-11-13)**: se mantiene la app oficial de Tailscale y OffCloud la controla desde dentro (opción "A"). Se descartó, por ahora, meter Tailscale dentro del APK (`tsnet`/`libtailscale`): no hay guía oficial para Android, hay un bloqueo de permisos conocido, el APK crece unos 20–30 MB y complica las subidas en segundo plano. Solo tendría sentido si se reparte la app a gente que no deba instalar Tailscale, y habría que hacer antes una prueba de concepto.
- **Sync**: manual — el usuario pulsa "Sincronizar", no hay auto-sync en background por ahora.
- **Usuarios**: multi-usuario real (más de una persona/familia sube fotos), con panel admin web.
- **Auth**: usuario/contraseña → login → token de sesión por login (no token fijo). Contraseñas con bcrypt. El mismo login sirve para la app Android y para el panel `/admin`.
- **Dedup**: SHA-256 por foto, único por `(owner_id, hash)` — el mismo archivo subido por usuarios distintos no se pisa.
- **Organización de fotos**: `data/photos/{username}/{año}/{mes}/{hash}.ext`. El año/mes sale de la fecha de la foto (ver "Fecha de la foto"); si no hay ninguna, de la fecha de subida.
- **Cliente Android**: Kotlin + Jetpack Compose (no Python/Kivy — mejor soporte de MediaStore/WorkManager).
- **Persistencia cliente**: Room (SQLite) trackea el estado de cada foto (`pending` / `uploaded` / `error`).
- **Subida en background**: WorkManager, con reintento automático (backoff exponencial) si falla; sobrevive al cierre de la app.
- **Carpetas en la nube (multi-dispositivo)**: carpeta = agrupación lógica en el server (`folders` + `folder_photos`, muchos-a-muchos: una foto puede estar en varias carpetas). El almacenamiento físico sigue siendo `año/mes/hash` y no depende de la carpeta. Se identifica por **nombre dentro del usuario** (`POST /folders` es get-or-create): dos móviles de la misma cuenta que añaden "Camera" comparten la misma carpeta.
- **Vínculo carpeta nube ↔ carpeta local**: es por dispositivo, guardado en `SharedPreferences` bajo `folder_links|{serverUrl}|{username}` (JSON `folderId → treeUri`). Un móvil nuevo ve las carpetas de la nube como "No vinculada" y elige qué carpeta local usar.
- **"Al día"**: comparación por hash entre la carpeta local y `/folders/{id}/manifest`. Se muestra como "↑ N por subir · ↓ M por bajar".
- **Subir y Bajar son aditivos**: nunca borran nada, ni en local ni en el server. Un modo "espejo" (que propague borrados) no está decidido.
- **Subir** primero llama a `/folders/{id}/link` con los hashes que faltan en la carpeta: lo que el server ya tiene (de otra carpeta u otro móvil) se enlaza sin reenviar bytes; solo se sube lo que vuelve en `missing`. Esto sustituye a la antigua reconciliación con `/photos/manifest` (una foto borrada desde el panel desaparece del manifest de la carpeta, así que se vuelve a subir igual).

## Fecha de la foto (cómo se decide la carpeta)

El cliente calcula `taken_at` con este orden de preferencia:
1. EXIF `DateTimeOriginal`
2. EXIF `DateTime`
3. Fecha de modificación del archivo (`DocumentFile.lastModified()`), porque muchas fotos llegan sin EXIF (WhatsApp, descargadas, editadas, capturas)

Si no hay ninguna, no manda `taken_at` y el server usa la fecha de subida. En Logcat (tag `OffCloud`) se ve de dónde salió la fecha de cada foto.

Cambiar la lógica solo afecta a fotos subidas a partir de ese momento. Las que ya están mal ubicadas en el server hay que borrarlas desde el panel admin y volver a sincronizar (el manifest hace que se re-suban y esta vez vayan a su carpeta correcta).

## Estructura del repo

```
host/
  .env.example
  .gitignore
  README.md
  requirements.txt            # fastapi, uvicorn, python-multipart, pydantic-settings, sqlalchemy, bcrypt
  start_server.bat            # doble click; usa `python -m uvicorn` (ver Device Guard más abajo)
  app/
    __init__.py
    main.py                   # endpoints FastAPI + bootstrap admin + print de URL del panel
    models.py                 # User, UserSession, Photo, Folder, FolderPhoto (SQLAlchemy)
    auth.py                   # bcrypt, create_session, get_current_user, require_admin
    config.py                 # pydantic-settings (.env, extra="ignore")
    database.py               # engine/session SQLAlchemy (SQLite)
    static/
      admin.html              # panel /admin (HTML+JS vanilla, login user/password, tema negro/morado)

client/
  build.gradle.kts            # plugin KSP a nivel raíz
  README.md
  app/
    build.gradle.kts          # Room, WorkManager, OkHttp, ExifInterface, DocumentFile, KSP
    src/main/java/com/diosg/offcloud/
      MainActivity.kt         # UI Compose: login + FoldersScreen (lista de carpetas de la nube)
      FolderSync.kt           # API de carpetas, FolderLinks, escaneo con caché de hash, push/pull, fecha EXIF
      PhotoSyncState.kt       # entidad Room (solo alimenta los contadores Subidas/Pendientes/Errores)
      PhotoSyncDao.kt         # DAO Room (getAllFlow, getByUri, upsert, deleteByUri, deleteAll)
      LocalFileHash.kt        # entidad+DAO Room: caché uri → hash (se invalida si cambia tamaño o lastModified)
      AppDatabase.kt          # base de datos Room, versión 2 (MIGRATION_1_2 crea local_file_hash)
      UploadWorker.kt         # CoroutineWorker: sube una foto (multipart con taken_at y folder_id opcionales)
      DownloadWorker.kt       # CoroutineWorker: baja una foto a la carpeta SAF (createFile; borra parciales)
      ConnectionStatus.kt     # ConnectionBanner: Tailscale instalado/conectado, ping a /health, botón Conectar
      SessionStore.kt         # token de sesión cifrado con AES-GCM del Android Keystore (+ migración del texto plano)
    src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml  # icono: nube tachada (vectorial)
    src/main/res/drawable/background.jpg      # wallpaper negro/morado
    src/main/res/xml/network_security_config.xml
    src/main/AndroidManifest.xml              # editado a mano: INTERNET, ACCESS_NETWORK_STATE, <queries> com.tailscale.ipn, networkSecurityConfig
```

## Configuración del server (`host/.env`)

```
INITIAL_ADMIN_USERNAME=...    # solo se usan si la tabla users está vacía (primer arranque)
INITIAL_ADMIN_PASSWORD=...
STORAGE_DIR=./data/photos
DB_PATH=./data/app.db
ADMIN_DISPLAY_PORT=8000       # solo informativo: puerto que se muestra en el log de arranque
```

**En la torre (server de producción, 2026-11-14)** el `.env` guarda todo en el disco D: `STORAGE_DIR=D:/OffCloud` y `DB_PATH=D:/OffCloud/data/app.db` (fotos en `D:/OffCloud/{username}/…`). El usuario lo quiere así, todo en ese disco. Riesgo conocido y aceptado: borrar un usuario hace `rmtree(STORAGE_DIR/{username})` sin validar el nombre, así que **no hay que crear nunca un usuario llamado `data`** (al borrarlo se borraría `app.db`); tampoco nombres vacíos, `.` o `..`. Se propuso validar los nombres y limitar el `rmtree`; de momento no se hace.

Las tablas `folders` y `folder_photos` son nuevas: `create_all` las crea solas en una BD existente, no hace falta borrar `app.db`. Las fotos subidas antes de que existieran las carpetas no están en ninguna; al "Añadir carpeta" y pulsar Subir se enlazan sin resubirse.

Los antiguos `AUTH_USERNAME` / `AUTH_PASSWORD` / `API_TOKEN` ya no existen. Si se migra desde la versión single-user hay que borrar `data/app.db` (el esquema cambió: `users`, `sessions`, `photos.owner_id`).

Al arrancar, el server imprime un bloque con la URL del panel (`http://localhost:8000/admin` y el recordatorio de la IP de Tailscale).

## API del server

```
POST   /auth/login            form: username, password → { token, is_admin }
POST   /photos/upload         multipart: file, device_id?, taken_at?, folder_id? (Bearer token). Con folder_id enlaza la foto a la carpeta, también si ya existía (already_exists)
GET    /photos/{id}/file      → el archivo (solo fotos del usuario autenticado; 404 si no)
GET    /photos/manifest       → { hashes: [...] }   (solo del usuario autenticado; el cliente ya no lo usa)
GET    /photos/stats          → { total_photos, total_size_bytes }

GET    /folders               → [{ id, name, photo_count, total_size_bytes, updated_at }]
POST   /folders               { name } → get-or-create por nombre
GET    /folders/{id}/manifest → { photos: [{ id, hash_sha256, filename, size_bytes, taken_at }] }
POST   /folders/{id}/link     { hashes } → { linked, missing }  (enlaza fotos que el usuario ya tiene; missing = hay que subirlas)

GET    /admin                 → página HTML del panel (pide user/password admin)
GET    /admin/users           (solo admin)
POST   /admin/users           { username, password, is_admin }  (solo admin)
DELETE /admin/users/{id}      (solo admin) borra en cascada: fotos (archivo + fila), carpetas lógicas y sus enlaces, carpeta física del usuario, sesiones y usuario. Un admin no puede borrarse a sí mismo (400).
GET    /admin/photos          (solo admin, fotos de TODOS los usuarios)
DELETE /admin/photos/{id}     (solo admin, borra archivo + fila + sus enlaces a carpetas)
```

Primer admin: se crea solo al arrancar si `users` está vacía, con `INITIAL_ADMIN_*`. Después se gestionan usuarios desde `/admin`.

## Cliente Android: comportamiento actual

- Login (usuario, contraseña) → guarda `server_url` y `username` en `SharedPreferences` (`offcloud_prefs`) y el token **cifrado** en la clave `token_enc` (ver más abajo). Si hay token pero no `username` (sesión de la versión anterior), pide login otra vez.
- **Token cifrado (2026-11-14)**: `SessionStore` cifra el token con AES-256-GCM y una clave del **Android Keystore** (alias `offcloud_session_key`, no se puede extraer del móvil). Se descartó `EncryptedSharedPreferences` porque Google deprecó toda la librería `androidx.security:security-crypto` y no tendrá más versiones; Google recomienda usar el Keystore directamente.
  - Migración automática: si queda la clave antigua `token` en texto plano, se cifra y se borra. Verificado en el móvil: tras abrir la app solo existe `token_enc`.
  - Si no se puede descifrar (p. ej. prefs restauradas de un backup en otro móvil, porque la clave del Keystore no viaja), se borra el token y se pide login.
  - Los workers **no reciben el token en `inputData`** (WorkManager lo guardaría sin cifrar en su BD): lo leen de `SessionStore` al ejecutarse. Así además usan siempre la sesión actual. Si no hay sesión, devuelven `failure` en vez de reintentar.
  - El sistema de permisos de Claude Code no deja leer `shared_prefs/offcloud_prefs.xml` por adb (contiene el token). Para comprobarlo, mirar solo los nombres de las claves: `grep -o 'name="[^"]*"'`.
- **URL del server automática (2026-11-14)**: por defecto `http://offcloud:8000` (`DEFAULT_SERVER_URL`), el nombre MagicDNS corto del PC servidor, así que no hace falta escribir la IP. El campo de URL está oculto: se ve "Server: offcloud:8000" y un botón "Cambiar" que lo muestra. Si la URL guardada (p. ej. la IP antigua) no responde pero `offcloud` sí, se cambia sola al abrir el login.
  - No se puede *descubrir* el server de otra forma: Tailscale no deja pasar broadcast/mDNS, la app de Tailscale en Android no ofrece a otras apps la lista de equipos, y barrer `100.64.0.0/10` (4 millones de IPs) no es viable.
  - **Requisito**: la máquina servidora tiene que llamarse `offcloud` en Tailscale (renombrarla en el panel de Tailscale, Machines → Edit machine name). MagicDNS está activo en la tailnet (`tail06f56a.ts.net`). El PC se llamaba `lenovo` el 2026-11-14.
  - `network_security_config.xml` permite HTTP sin cifrar hacia `offcloud`, `*.ts.net` y la IP `100.119.30.85` (respaldo).
  - **Mover el server a otro PC** (el usuario quiere llevarlo a una torre que también está en la tailnet): la app busca el equipo que se llame `offcloud`, no un PC concreto. Hay que ponerle `offcloud` al PC nuevo (los nombres no se repiten: un segundo `offcloud` pasa a `offcloud-1`) y **copiar `host/data/` entero** (`app.db` + `photos/`). Con una BD vacía, el token del móvil deja de valer (hay que volver a entrar) y, peor, los vínculos de carpetas del móvil van por id de carpeta (`folder_links|{serverUrl}|{username}`, y la URL es la misma): con ids nuevos, una carpeta local podría quedar vinculada a otra carpeta de la nube que no le corresponde. En ese caso, desvincular y volver a añadir.
  - Para probar contra el server de desarrollo mientras la torre es `offcloud`: "Cambiar" → `http://lenovo.tail06f56a.ts.net:8000` (el nombre corto `lenovo` no está permitido en `network_security_config.xml`).
- Pantalla de carpetas (`FoldersScreen`): al entrar carga `GET /folders` y, por cada carpeta vinculada en este móvil, calcula el estado (Comprobando / ✓ Al día / ↑N ↓M / error si la carpeta local ya no es accesible).
  - **Pestañas** (`PrimaryScrollableTabRow`, se desplaza en horizontal): una por carpeta de la nube, con una marca de estado delante del nombre (✓ al día, ● hay cambios, ! error, ○ sin vincular, … comprobando), y una última **"+ Añadir"**. Así la pantalla no crece con el número de carpetas. La pestaña elegida se guarda por id de carpeta (no por índice), así que no salta al recargar; al añadir una carpeta se abre su pestaña.
  - Panel de la carpeta (`FolderPanel`): fotos y tamaño en la nube, estado, carpeta local, Subir ↑ y Bajar ↓ (solo activos si hay algo que hacer), Cambiar carpeta y Desvincular.
  - Panel "+ Añadir" (`AddFolderPanel`): SAF → crea o reutiliza la carpeta de la nube con el nombre de la local.
  - Debajo: mensaje del último resultado, contadores en vivo, Actualizar, Limpiar contadores y Cerrar sesión.
- El permiso SAF se pide de lectura **y escritura** (escritura para poder bajar).
- Cuando los pendientes de subida llegan a 0, refresca solo. Las descargas no tienen contador: hay que pulsar Actualizar.
- Subida: `enqueueUniqueWork(uri, REPLACE)` (con `KEEP` no se re-subían fotos ya procesadas). Bajada: `enqueueUniqueWork("download|treeUri|hash", KEEP)` para no duplicar archivos si se pulsa dos veces. Si el nombre ya existe en la carpeta local, SAF crea `nombre (1).jpg`.
- `device_id` que se manda al server: `"{Build.MANUFACTURER} {Build.MODEL}"`.
- Solo se miran archivos `image/*` del primer nivel de la carpeta (sin subcarpetas).
- **Se ignoran los archivos ocultos** (nombre que empieza por `.`). Son la papelera de Android: `.trashed-<caducidad>-IMG_….jpg`, fotos borradas desde la galería que se guardan unos 30 días; y los `.pending-…` de fotos a medio guardar. La galería no los enseña pero SAF sí, así que la app contaba fotos de más (bug visto el 2026-11-14: "tengo 2 fotos en DCIM y la app ve 3"). Si una versión anterior los había encolado, `scanLocalFolder` cancela su trabajo y borra su fila de Room.
- **Timeouts de subida/bajada**: `UploadWorker.transferClient` (lo comparte `DownloadWorker`) usa connect 15 s y write/read 2 min. Con los 10 s por defecto de OkHttp, una foto de unos 6 MB por datos móviles a través de Tailscale fallaba siempre con `timeout` y WorkManager la reintentaba sin fin.
- Depurar en el móvil: el móvil suele estar conectado por adb (`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`). Como el APK es debug, se puede leer la BD de Room: `adb exec-out run-as com.diosg.offcloud cat databases/offcloud.db` (y también `-wal` y `-shm`). La tabla `photo_sync_state` tiene `errorMsg`.
- **Bloque de conexión** (`ConnectionBanner`, en el login y en la pantalla de carpetas). Se comprueba cada 5 s, solo con la app en primer plano (`repeatOnLifecycle(RESUMED)`):
  - Si Tailscale **no está instalado** (paquete `com.tailscale.ipn`): aviso rojo "Falta Tailscale" con botón a la Play Store. Al volver de instalarlo se actualiza solo.
  - "● Tailscale conectado / ○ desconectado": en realidad mira si hay *alguna* VPN activa (`TRANSPORT_VPN`); Android no dice cuál.
  - "● Server en línea · N ms / ○ El server no responde": `GET /health` con timeout de 3 s. Si Tailscale está conectado pero el server no contesta, sugiere revisar el PC y `start_server.bat`.
  - Botón **Conectar**: envía un único broadcast explícito `com.tailscale.ipn.CONNECT_VPN` a `com.tailscale.ipn.IPNReceiver` (el que usan Tasker y Home Assistant; **no documentado oficialmente**). Espera 8 s; si no aparece la VPN, abre la app de Tailscale. No se reenvía en bucle porque hay reportes de que eso deja a Tailscale conectando sin parar.
  - En la pantalla de carpetas, cuando el server pasa de caído a en línea, se recargan carpetas y estados.
- La clave antigua `folder_uri` de `SharedPreferences` ya no se usa (la carpeta se vuelve a añadir desde "Añadir carpeta").
- Estética: wallpaper `background.jpg` a pantalla completa (`ContentScale.Crop`) + velo `#990D0B14`. Acento `#A855F7`, secundario `#C4B5FD`. Tipografía: familias nativas de Android (`sans-serif-black` / `sans-serif-light`), sin assets de fuentes.
  - **Tarjeta base translúcida** (efecto cristal, pedido por el usuario): `#8C1A1625` (~55 % de opacidad) con un borde blanco `#26FFFFFF`, **sin sombra** (con fondo translúcido la sombra se ve a través). No hay desenfoque real del fondo: Compose no desenfoca lo que hay detrás de un elemento.
  - **Bloques interiores** (`Modifier.innerPanel()`): borde fino morado `#4DC4B5FD` + relleno blanco muy sutil `#0FFFFFFF`. Se cambió porque el relleno anterior, casi del mismo color que la tarjeta, no dejaba claro qué estaba dentro de qué. El aviso de "Falta Tailscale" usa el mismo panel con borde rojo.
- **Icono (2026-11-14)**: "nube tachada" (juego de palabras con Off-Cloud), vectorial y adaptativo. Fondo con degradado `#2E1752 → #0D0B14`; nube = icono `cloud` de Material escalado x1.9 en trazo `#C4B5FD`; barra diagonal `#A855F7` con un "hueco" oscuro `#180F29` que la separa de la nube. Hay una capa **monocroma** propia (`ic_launcher_monochrome.xml`) para los iconos temáticos de Android 13+: en ella el hueco se recorta con `clip-path`, porque en monocromo un trazo oscuro saldría sólido. Se borraron los `.webp` del robot (solo se usaban por debajo de API 26 y minSdk es 26). Verificado en el móvil con iconos temáticos (versión monocroma); la versión en color no se ha visto en pantalla.
- El wallpaper es una imagen descargada de internet: vale para uso personal, pero habría que cambiarla por una propia o con licencia si algún día se publica la app.

## Entorno de desarrollo (específico de esta máquina)

- Windows 11, proyecto en `C:\Projects\OffCloud\`, Python 3.14
- Teléfono de pruebas: Nothing Phone 1
- Package Android: `com.diosg.offcloud`, minSdk 26, targetSdk/compileSdk 37, AGP 9.3.1
- Kotlin `2.2.10` → KSP `2.2.10-2.0.2` (tiene que coincidir exactamente). Se usa version catalog (`gradle/libs.versions.toml`), pero KSP y las dependencias de Room/WorkManager están escritas directo en los `.kts`.
- Compilar por línea de comandos: `java` no está en el PATH; usar el JDK de Android Studio: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug`
- Room fijado en **2.7.1** (con 2.6.1 falla KSP2: "unexpected jvm signature V" en `suspend fun` que devuelven `Unit`)
- `gradle.properties`: `android.disallowKotlinSourceSets=false` (conflicto KSP vs. "built-in Kotlin" de AGP nuevo). **Sin espacio al final de la línea**: Gradle falla con `'false '`.
- **El server ya está en la torre (2026-11-14)**: Windows `DESKTOP-PS9EO7I`, nombre en Tailscale `offcloud`, IP `100.126.61.126`. El portátil `lenovo` (`100.119.30.85`, la IP que sigue escrita como respaldo en `network_security_config.xml` y que ya no sirve) queda como PC de desarrollo. La torre empezó con la **BD vacía** a propósito: tampoco es la BD final, se sigue probando y más adelante se creará una BD de "producción".
- En la torre **no hay Android SDK ni adb**: no se puede leer la BD de Room del móvil hasta instalar Android Studio o las platform-tools.
- **Firewall de la torre (arreglado el 2026-11-14)**: se deshabilitaron las dos reglas "Query User" de `python314\python.exe` (TCP y UDP), que dejaban entrar desde cualquier dirección en los perfiles privado y público, y se creó `OffCloud Server`: TCP 8000 solo desde `100.64.0.0/10`, perfil Any. Verificado en el móvil: con Tailscale, "Server en línea"; sin Tailscale, por wifi de casa a la IP local, no responde. Si Windows vuelve a preguntar si Python puede usar la red (p. ej. al actualizar Python), hay que denegarlo o volver a deshabilitar esas reglas. IPs de la torre en la red de casa: `192.168.1.33` y `192.168.1.35` (dos adaptadores wifi).
- La app ya no depende de la IP: usa el nombre MagicDNS `offcloud`.
- Tailscale CLI en este PC: `"/c/Program Files/Tailscale/tailscale.exe" status` (nombre, IP, MagicDNS).
- Firewall de Windows en el lenovo: regla de entrada `OffCloud Server`, TCP 8000, con Ámbito restringido a `100.64.0.0/10` (rango de Tailscale). Hubo que deshabilitar dos reglas de **bloqueo** residuales de `python.exe`, que ganaban sobre la regla de permiso.
- Si `uvicorn.exe` da error de "Device Guard"/Smart App Control: usar `python -m uvicorn` (ya está en `start_server.bat`).
- Guía paso a paso para replicar todo en otro PC: `setup-guide.md`.

## Verificado y funcionando

- Login, upload, dedup por hash, manifest, stats (curl).
- Multi-usuario: aislamiento de fotos y manifest entre usuarios (curl).
- Panel admin: login con user/password, crear usuario, borrar usuario en cascada, listar y borrar fotos (curl; el HTML se comprobó que sirve, la interacción visual la probó el usuario).
- Organización por `taken_at` vs. fecha de subida en el server (curl, con y sin `taken_at`).
- Room + WorkManager: la segunda sincronización no resube lo ya subido (probado en dispositivo real).
- Acceso remoto por Tailscale con datos móviles.
- Login desde la app con usuario/contraseña, subida de fotos reales, wallpaper y tema.
- Carpetas en el server (curl): get-or-create por nombre, link sin resubir, manifest de carpeta, descarga, aislamiento entre usuarios, cascadas al borrar foto/usuario.
- Server en la torre `offcloud` (2026-11-14): `http://offcloud:8000/health` y `http://offcloud.tail06f56a.ts.net:8000/health` responden desde el PC; el móvil entra y sube contra él. Los archivos guardados tienen el SHA-256 que dice su nombre.
- **Restaurar con Bajar** (2026-11-14, confirmado por el usuario): una carpeta nueva y vacía en el móvil, vinculada a `DCIM`, recibió las 2 fotos y tras Actualizar quedó "✓ Al día" (el hash coincide).
- En el móvil (2026-11-14): botón **Conectar** de Tailscale (el broadcast `CONNECT_VPN` funciona con la versión instalada), **Añadir carpeta** y **Subir**. Implica que la migración Room 1→2 sobre la BD existente funcionó (la app arrancó y usó la caché de hashes).

## Tareas de testing pendientes

Checklist para probar en el móvil. **Mantenerla al día**: al añadir una funcionalidad, apuntar aquí cómo probarla; cuando el usuario confirme una, moverla a "Verificado y funcionando" con la fecha.

**⚠ PRIORIDAD antes de pasar a producción (2026-11-14)**, en este orden (las pruebas concretas están en las secciones de abajo):
1. **Restaurar**: Bajar en un segundo dispositivo o una instalación limpia → están todas las fotos, se abren y la carpeta queda "✓ Al día" (eso confirma que el hash coincide). *Hecho a pequeña escala (2 fotos, carpeta nueva en el mismo móvil, 2026-11-14: bajaron y quedó "✓ Al día"); falta con muchas fotos o en otro dispositivo.*
2. **Interrupciones**: cortar la red, cerrar la app y tirar el server a mitad de subida → termina sola, sin pérdidas ni duplicados, y cuadra con el panel admin.
3. **Volumen real**: la carpeta `Camera` (unos 25 GB, miles de archivos): tiempo del primer y del segundo cálculo de estado, cola de cientos de subidas, fotos grandes por datos móviles.
4. **Ensayo de migración entre PCs**: la torre ya es `offcloud`, pero empezó con la BD vacía, así que copiar los datos (ahora `D:/OffCloud/` entero) a otro PC sin que el móvil note nada sigue sin probarse. Antes conviene hacer que el móvil detecte el cambio de server (identificador propio del server) para evitar vínculos de carpetas cruzados.
5. **Fechas**: una foto sin EXIF (WhatsApp) acaba en el año/mes correcto.

No son tests pero hacen falta para producción: **copia de seguridad de `D:/OffCloud/`** (el usuario la quiere; ahora es un único disco) y **separar dev y prod en el móvil** (dos versiones de la app que se instalan a la vez, OffCloud y OffCloud Dev, con distinto server por defecto). Propuesto, sin hacer.

**Server automático en el login (2026-11-14)** — requiere renombrar antes el PC a `offcloud` en Tailscale
- [x] Desde el PC: `http://offcloud:8000/health` responde (comprueba MagicDNS). Verificado 2026-11-14 en la torre.
- [ ] En el móvil, con la IP antigua guardada: al abrir el login aparece "Server: offcloud:8000" solo, y el bloque de conexión dice "Server en línea".
- [ ] Instalación limpia: entrar solo con usuario y contraseña.
- [ ] El nombre corto `offcloud` resuelve en Android. Si no, probar con "Cambiar" y `http://offcloud.tail06f56a.ts.net:8000`, y cambiar `DEFAULT_SERVER_URL` a ese.
- [ ] "Cambiar" deja escribir otra URL y el login la usa.

**Token cifrado e icono (2026-11-14)**
- [x] Migración del token en texto plano a `token_enc` (verificado por adb).
- [ ] Cerrar sesión y volver a entrar; con subidas en cola, que terminen con el token nuevo.
- [ ] Icono a color: desactivar los iconos temáticos y comprobar cómo se ve.

**Interfaz (2026-11-14)**
- [ ] Pestañas con varias carpetas (3 o más): se desplazan en horizontal, la marca de estado de cada pestaña es correcta, la pestaña elegida se mantiene al pulsar Actualizar.
- [ ] "+ Añadir" abre su panel; tras elegir carpeta se abre la pestaña de la carpeta nueva.
- [ ] Tarjeta translúcida y bordes de los paneles: se lee bien sobre las zonas claras del wallpaper.

**Carpetas multi-dispositivo**
- [x] Vincular una carpeta vacía y **Bajar** descarga las fotos a esa carpeta (2026-11-14, en el mismo móvil con una carpeta nueva).
- [ ] Lo mismo en un segundo dispositivo o instalación limpia: ve las carpetas como "No vinculada".
- [ ] Bajar con un nombre que ya existe en la carpeta local: se crea `nombre (1).jpg` y no se pisa.
- [ ] Pulsar Bajar dos veces seguidas no duplica archivos.
- [x] Tras terminar las descargas y pulsar Actualizar: "✓ Al día" (2026-11-14).
- [ ] Borrar una foto desde el panel admin → la carpeta pasa a "↑ 1 por subir" → Subir la vuelve a subir.
- [ ] Añadir en el móvil B una carpeta con el mismo nombre que una del móvil A: se reutiliza la de la nube (no se duplica) y Subir solo enlaza lo que ya estaba.
- [ ] Desvincular y volver a vincular una carpeta.
- [ ] Revocar el permiso de la carpeta (o borrarla) → la pestaña muestra "!" y el mensaje de volver a vincular.

**Conexión / Tailscale**
- [ ] Aviso "Falta Tailscale" (desinstalando Tailscale) y que desaparezca solo al reinstalarlo y volver a OffCloud.
- [ ] Cerrar `start_server.bat` con la app abierta → "El server no responde"; volver a abrirlo → "en línea" y se recargan las carpetas solas.
- [ ] Tailscale conectado pero server apagado: aparece la sugerencia de revisar el PC.

**Subidas en segundo plano (Room/WorkManager)**
- [ ] Cortar la red a mitad de subida: ¿reintenta solo al volver?
- [ ] Cerrar la app con subidas pendientes: ¿terminan igual?
- [ ] Contrastar los contadores con `/photos/stats` (o el panel admin).
- [ ] Server cayéndose a mitad de una subida.

**Fechas y volumen**
- [ ] Fallback a la fecha de modificación del archivo (foto sin EXIF, p. ej. de WhatsApp): acaba en el año/mes correcto del server.
- [ ] Carpeta grande (cientos de fotos): tiempo del primer cálculo de estado y de los siguientes (con caché).
- [ ] Fotos muy pesadas (más de 6 MB) por datos móviles: se suben sin `timeout` con los timeouts nuevos de 2 min. Los vídeos se ignoran a propósito (solo `image/*`).
- [ ] Borrar una foto desde la galería → la carpeta no la cuenta (va a `.trashed-…`) y no se sube.

## Pendiente (desarrollo)

- **Ideas no hechas**: modo espejo (propagar borrados), progreso de descargas, ver/renombrar/borrar carpetas desde el panel admin (el panel no muestra carpetas), subcarpetas, refrescar solo al terminar descargas, desconectar Tailscale desde OffCloud.
- **Rendimiento**: `localFolderName()` hace una consulta SAF en cada recomposición; el primer cálculo de estado de una carpeta grande lee todos los archivos para hashearlos (después tira de la caché).
- **Cámara vieja con reloj mal configurado** (marca 2016 en vez de 2026, desfase variable, no se puede corregir en la cámara): su EXIF no sirve para organizar por fecha. Pospuesto a propósito. Se descartó la corrección automática por desfase fijo (el desfase varía). Idea pendiente de decidir: fecha manual por sincronización, pero el usuario no estuvo de acuerdo con la primera propuesta, así que hay que replantearlo. Además se mezclarán fotos de esta cámara con fotos de móviles con fecha correcta.
- **Panel admin**: no hay forma de cambiar la contraseña de un usuario ni de borrar sesiones antiguas (la tabla `sessions` solo crece).
- **Extras de UI no urgentes**: barra de progreso por foto, botón "reintentar solo los errores".
