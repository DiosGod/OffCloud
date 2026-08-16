# host/ — Server de backup de fotos (Fase 0 + Fase 1)

Server en FastAPI: login con usuario/contraseña fijos, subida de fotos con
deduplicación por hash SHA-256, y manifest para que el cliente sepa qué ya
está subido sin tener que volver a mandarlo.

## Instalación

```bash
cd host
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

cp .env.example .env
# Edita .env: pon tu usuario, contraseña y un API_TOKEN largo y random
# (puedes generarlo con: python3 -c "import secrets; print(secrets.token_hex(32))")
```

## Ejecutar

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Para producción, bindea solo a tu IP de Tailscale (`tailscale ip -4`) en vez
de `0.0.0.0`, así el server no escucha en tu red local ni en internet:

```bash
uvicorn app.main:app --host 100.x.x.x --port 8000
```

## Probar manualmente

```bash
# Health check
curl http://127.0.0.1:8000/health

# Login
curl -X POST http://127.0.0.1:8000/auth/login \
  -d "username=tu_usuario&password=tu_password" \
  -H "Content-Type: application/x-www-form-urlencoded"

# Upload (con el token que te devolvió el login, o el de .env)
# device_id y taken_at son opcionales
curl -X POST http://127.0.0.1:8000/photos/upload \
  -F "file=@/ruta/a/una/foto.jpg" \
  -F "device_id=mi-pixel" \
  -H "Authorization: Bearer TU_TOKEN"

# Si subes la misma foto (mismo contenido) otra vez, el server responde
# {"status": "already_exists", ...} y NO crea una copia nueva en disco.

# Manifest: lista de hashes que el server ya tiene
curl http://127.0.0.1:8000/photos/manifest -H "Authorization: Bearer TU_TOKEN"

# Stats
curl http://127.0.0.1:8000/photos/stats -H "Authorization: Bearer TU_TOKEN"
```

Las fotos se guardan en `STORAGE_DIR` (por defecto `./data/photos`),
organizadas en `AAAA/MM/<hash>.ext`. La metadata vive en una DB SQLite
en `DB_PATH` (por defecto `./data/app.db`).

## Qué falta (próximas fases)

- Fase 2: esqueleto del cliente Android, subida sin trackear estado
  todavía (para validar la conexión vía Tailscale).
- Fase 3: cliente con Room + WorkManager, comparando contra `/manifest`
  antes de subir.

Ver `spec-photo-backup.md` en la raíz del repo para el detalle completo.
