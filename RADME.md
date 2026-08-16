# Spec: Backup de fotos self-hosted (Android → Server propio)

## 1. Decisiones ya tomadas

- **Red**: acceso vía Tailscale (VPN privada). El server nunca se expone directamente a internet.
- **Sincronización**: manual. El usuario abre la app y pulsa "sincronizar".
- **Alcance**: proyecto propio, un solo usuario, prioridad en aprender el flujo completo.

## 2. Arquitectura general

```
[Android APK]                    [Server - PC/NAS]
  |                                    |
  |-- selecciona carpeta local         |
  |-- escanea fotos → SQLite local     |
  |-- calcula SHA-256 por foto         |
  |                                    |
  |-- (usuario pulsa "Sincronizar")    |
  |-- POST /auth/login ---------------→|  valida user/pass → devuelve token
  |←----------------------------------|
  |-- GET /photos/manifest -----------→|  server devuelve lista de hashes que ya tiene
  |←----------------------------------|
  |-- filtra localmente qué falta      |
  |-- POST /photos/upload (por foto)-->|  guarda archivo + registra en DB
  |←----------------------------------|
  |-- marca foto como "subida" local   |
```

Todo el tráfico va sobre la IP de Tailscale (ej. `100.x.x.x`), así que ni
siquiera hace falta HTTPS con certificado público — pero igual conviene
usar TLS autofirmado o un cert de Tailscale (`tailscale cert`) por buena
práctica, ya que da cifrado extremo a extremo real.

## 3. Stack recomendado

| Componente          | Elección                          | Por qué |
|---------------------|------------------------------------|---------|
| Server framework    | **FastAPI** (Python)              | Async, tipado, genera docs OpenAPI solas, encaja con "hazlo en Python" |
| Server DB           | **SQLite** para empezar           | Cero configuración; migrar a Postgres si crece |
| Almacenamiento fotos| Filesystem plano en el NAS         | `/data/photos/{año}/{mes}/{hash}.jpg` |
| Auth                | Token opaco (no hace falta JWT)   | Un solo usuario, no necesitas expiración compleja |
| Cliente Android     | **Kotlin** + Jetpack Compose        | WorkManager, Room (SQLite) y MediaStore API están mejor soportados en Kotlin que si intentas hacerlo en Python/Kivy |
| Cola de subida      | **WorkManager**                    | Reintentos, backoff, respeta batería/red, estándar en Android |
| DB local (cliente)  | **Room** (sobre SQLite)            | Persistencia del estado de cada foto |

> Nota: el cliente Android en Python (Kivy/BeeWare) es posible pero vas a
> pelear más con el acceso a MediaStore, permisos de almacenamiento y
> background work. Para esta pieza específica, Kotlin nativo te ahorra
> mucho dolor. El **server sí** lo hacemos en Python como querías.

## 4. Modelo de datos

### Server (tabla `photos`)
| campo         | tipo      | nota                          |
|---------------|-----------|-------------------------------|
| id            | int PK    |                                |
| hash_sha256   | string    | único, índice                 |
| filename      | string    | nombre original                |
| filepath      | string    | ruta física en disco           |
| size_bytes    | int       |                                |
| taken_at      | datetime  | de EXIF si existe              |
| uploaded_at   | datetime  |                                |
| device_id     | string    | por si algún día hay >1 móvil  |

### Cliente (tabla local `photo_sync_state`)
| campo         | tipo      | nota                          |
|---------------|-----------|-------------------------------|
| local_uri     | string PK | URI de MediaStore              |
| hash_sha256   | string    |                                |
| status        | enum      | `pending` / `uploaded` / `error` |
| last_attempt  | datetime  |                                |
| error_msg     | string?   |                                |

## 5. Contrato de API

```
POST /auth/login
  body: { "username", "password" }
  → { "token": "..." }

GET /photos/manifest
  header: Authorization: Bearer <token>
  → { "hashes": ["a1b2...", "c3d4..."] }
  # el cliente compara esto contra su DB local antes de subir nada

POST /photos/upload
  header: Authorization: Bearer <token>
  multipart: { file, hash_sha256, taken_at }
  → 201 { "id", "hash_sha256", "status": "stored" }
  → 200 { "status": "already_exists" }   # idempotente

GET /photos/stats
  → { "total_photos", "total_size_bytes" }   # opcional, para verificar en la app
```

## 6. Flujo detallado del cliente

1. Usuario elige carpeta(s) origen (ej. `DCIM/Camera`, `Pictures/WhatsApp`).
2. Al pulsar "Sincronizar":
   a. Escanea la carpeta vía MediaStore.
   b. Para cada foto nueva (no en `photo_sync_state`), calcula SHA-256 y la inserta como `pending`.
3. Pide `GET /photos/manifest` al server.
4. Marca como `uploaded` (sin subir) cualquier `pending` cuyo hash ya esté en el manifest — esto cubre el caso de reinstalar la app.
5. Encola en WorkManager el resto de `pending` como trabajos de subida.
6. Cada trabajo hace `POST /photos/upload`; si tiene éxito → `uploaded`; si falla → `error` + reintento con backoff.
7. Al final, muestra resumen: X subidas, Y ya existían, Z con error.

## 7. Roadmap sugerido (por fases)

**Fase 0 — Server mínimo**
- FastAPI con `/auth/login` (usuario hardcodeado en `.env`) y `/photos/upload` que solo guarda el archivo en disco. Sin manifest, sin hash todavía.
- Probar con `curl` o Postman, sin tocar Android aún.

**Fase 1 — Server completo**
- Añadir tabla `photos`, hash, endpoint `/manifest`, dedup real.

**Fase 2 — Cliente Android mínimo**
- App con selector de carpeta, botón "Sincronizar", sube todo sin trackear estado (para validar el flujo de red vía Tailscale).

**Fase 3 — Cliente con estado local**
- Añadir Room, cálculo de hash, comparación con manifest, WorkManager con reintentos.

**Fase 4 — Pulido**
- Pantalla de progreso/resumen, manejo de errores visible, opción de reintentar fallidos, borrar de origen tras confirmar subida (opcional, con cuidado).

## 8. Seguridad — checklist mínimo

- [ ] Token guardado en Android Keystore, no en SharedPreferences plano.
- [ ] Server solo escucha en la interfaz de Tailscale, no en `0.0.0.0` público.
- [ ] Rate limiting básico en `/auth/login` (evitar fuerza bruta aunque sea uso personal).
- [ ] Backups del propio server (RAID del NAS no es backup — considera una copia fuera de casa también).

## 9. Extensiones futuras (no ahora)

- Miniaturas + galería web simple para ver las fotos desde el navegador.
- Soporte multi-dispositivo (más de un móvil subiendo).
- Compresión/transcodificación opcional antes de subir.
- Auto-sync en background (cuando ya no quieras que sea manual).
