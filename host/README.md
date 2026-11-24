# host/ — Server de OffCloud

Server en FastAPI: multi-usuario con login (usuario/contraseña → token de
sesión), subida de fotos con deduplicación por SHA-256, carpetas lógicas
compartidas entre dispositivos y panel admin en `/admin`. Detalle de la API y
de las decisiones de diseño en `CLAUDE.md` (raíz del repo).

## Producción (torre, `D:\OffCloud`)

Todo vive en el disco D:, separado del checkout de desarrollo:

```
D:\OffCloud\
  server\    código de una versión etiquetada + venv + .env  (VERSION dice cuál)
  photos\    {usuario}\{año}\{mes}\{hash}.ext
  db\        app.db
  logs\      server.log, backup.log
  backups\   app-AAAAMMDD-HHMMSS.db (las 30 últimas)
```

`D:\OffCloud\server\.env`:

```
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_PASSWORD=...        # solo se usa si la BD está vacía
STORAGE_DIR=D:/OffCloud/photos
DB_PATH=D:/OffCloud/db/app.db
ADMIN_DISPLAY_PORT=8000
```

### Instalar / actualizar una versión

```powershell
git tag -a v0.2.0 -m "..."            # en main
powershell -ExecutionPolicy Bypass -File host\deploy\deploy.ps1 -Version v0.2.0
```

`deploy.ps1` reemplaza `server\app` y `server\deploy`, conserva `.env` y el
venv, e instala `requirements.txt`. No toca fotos, BD ni backups. Después hay
que reiniciar el server volviendo a ejecutar `install_tasks.ps1` como
administrador (ver abajo). Solo `Stop-ScheduledTask` no basta: cierra el `cmd`
pero puede dejar vivo el `python` con el código viejo.

### Arranque automático y backups (una sola vez, como administrador)

```powershell
powershell -ExecutionPolicy Bypass -File D:\OffCloud\server\deploy\install_tasks.ps1
```

Registra dos tareas programadas que corren como SYSTEM (sin iniciar sesión):

- **OffCloud Server**: al encender Windows lanza `deploy\run_server.bat`
  (uvicorn en `0.0.0.0:8000`, log en `logs\server.log`) y, desde ahí, lo
  vuelve a intentar cada 5 minutos si no está corriendo.

**El disco D: es un USB externo** (WD My Passport). Qué pasa si no está:

- Al encender sin el disco: el código también está en D:, así que no arranca
  nada y no se escribe nada en C:. En cuanto se conecta, la tarea lo arranca en
  5 minutos como mucho.
- Si se desconecta con el server en marcha: `/health` responde 503 con el
  motivo (la app muestra "El server no responde"), y un vigilante interno para
  el server tras dos comprobaciones fallidas (unos 30-60 s). Al volver el
  disco, la tarea lo arranca de nuevo.
- Si al volver a conectarlo Windows le diera otra letra, no arrancaría: hay
  que devolverle la D: en Administración de discos.
- **OffCloud Backup DB**: cada día a las 04:00 copia `app.db` a `backups\`
  con `deploy\backup_db.py` (copia coherente aunque el server esté escribiendo).
  **Solo la BD**: las fotos no tienen copia fuera de D:.

Requisitos de la torre: Tailscale en modo *unattended* (para que siga
conectado sin sesión iniciada), suspensión desactivada con corriente alterna,
y la regla de firewall `OffCloud Server` (TCP 8000 solo desde `100.64.0.0/10`).

### Restaurar la BD desde un backup

La BD va en modo WAL (desde v0.1.1): junto a `app.db` hay `app.db-wal` y
`app.db-shm`, que forman parte de ella mientras el server corre. Para
restaurar: parar la tarea, borrar `app.db`, `app.db-wal` y `app.db-shm`,
copiar el backup como `db\app.db` y volver a arrancarla. Nunca copiar `app.db`
a mano con el server en marcha: usar `deploy\backup_db.py`.

## Desarrollo

```powershell
cd host
python -m venv venv
venv\Scripts\pip install -r requirements.txt
# crea host\.env (sin STORAGE_DIR/DB_PATH usa ./data/photos y ./data/app.db)
start_server.bat
```

## Probar manualmente

```bash
curl http://127.0.0.1:8000/health

# Login → {"token": ..., "is_admin": ...}
curl -X POST http://127.0.0.1:8000/auth/login \
  --form-string "username=TU_USUARIO" --form-string "password=TU_CONTRASEÑA"

# Subida (taken_at y folder_id opcionales). La misma foto otra vez responde
# {"status": "already_exists"} y no se guarda una copia nueva.
curl -X POST http://127.0.0.1:8000/photos/upload \
  -H "Authorization: Bearer TU_TOKEN" \
  -F "file=@/ruta/a/foto.jpg" -F "taken_at=2026-05-01T10:00:00"

curl http://127.0.0.1:8000/photos/stats -H "Authorization: Bearer TU_TOKEN"
curl http://127.0.0.1:8000/folders      -H "Authorization: Bearer TU_TOKEN"
```

## Nombres de usuario

El nombre es también la carpeta en disco, así que solo se admiten 1-32
caracteres de `A-Z a-z 0-9 . - _`, empezando por letra o número, sin acabar en
`.` y sin nombres reservados de Windows (`con`, `nul`, `com1`...). No se
distinguen mayúsculas: si existe `ana` no se puede crear `Ana`.
