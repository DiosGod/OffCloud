"""Copia de seguridad de la base de datos (app.db) con la API de backup de SQLite.

Es seguro ejecutarlo con el server en marcha: SQLite hace una copia coherente
aunque haya escrituras a la vez (copiar el archivo a mano no lo es).

Uso:
    python backup_db.py <carpeta_backups> [--db RUTA_APP_DB] [--keep 30]

Si no se pasa --db, se usa DB_PATH del .env del server (la carpeta padre de
este script). Guarda app-AAAAMMDD-HHMMSS.db y conserva solo las --keep más
recientes.

Ojo: solo copia la base de datos, no las fotos.
"""

import argparse
import sqlite3
import sys
from datetime import datetime
from pathlib import Path


def default_db_path() -> Path:
    server_dir = Path(__file__).resolve().parent.parent
    sys.path.insert(0, str(server_dir))
    from pydantic_settings import BaseSettings, SettingsConfigDict

    class _Settings(BaseSettings):
        db_path: str = "./data/app.db"
        model_config = SettingsConfigDict(env_file=server_dir / ".env", extra="ignore")

    db = Path(_Settings().db_path)
    return db if db.is_absolute() else server_dir / db


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("backup_dir", type=Path)
    parser.add_argument("--db", type=Path, default=None)
    parser.add_argument("--keep", type=int, default=30)
    args = parser.parse_args()

    db_path = args.db or default_db_path()
    if not db_path.exists():
        print(f"No existe la base de datos: {db_path}", file=sys.stderr)
        return 1

    args.backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    dest = args.backup_dir / f"app-{stamp}.db"
    tmp = dest.with_suffix(".db.partial")

    src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    out = sqlite3.connect(tmp)
    try:
        src.backup(out)
    finally:
        out.close()
        src.close()
    tmp.replace(dest)

    backups = sorted(args.backup_dir.glob("app-*.db"))
    for old in backups[: max(0, len(backups) - args.keep)]:
        old.unlink()

    print(f"{datetime.now().isoformat(timespec='seconds')} backup OK: {dest} "
          f"({dest.stat().st_size} bytes, {min(len(backups), args.keep)} guardados)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
