import hashlib
import shutil
import time
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from sqlalchemy import func
from sqlalchemy.orm import Session

from .auth import verify_credentials, verify_token
from .config import settings
from .database import Base, engine, get_db
from .models import Photo

app = FastAPI(title="Photo Backup Server")

Base.metadata.create_all(bind=engine)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/auth/login")
def login(username: str = Form(...), password: str = Form(...)):
    if not verify_credentials(username, password):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    return {"token": settings.api_token}


@app.post("/photos/upload")
def upload_photo(
    file: UploadFile = File(...),
    device_id: str | None = Form(default=None),
    taken_at: str | None = Form(default=None),
    token: str = Depends(verify_token),
    db: Session = Depends(get_db),
):
    storage_dir = Path(settings.storage_dir)
    tmp_dir = storage_dir / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # 1. Volcamos a un archivo temporal calculando el hash al vuelo,
    #    sin cargar la foto entera en memoria.
    tmp_path = tmp_dir / f"upload_{int(time.time() * 1000)}"
    sha256 = hashlib.sha256()
    size_bytes = 0
    with tmp_path.open("wb") as buffer:
        while chunk := file.file.read(1024 * 1024):
            sha256.update(chunk)
            size_bytes += len(chunk)
            buffer.write(chunk)
    file_hash = sha256.hexdigest()

    # 2. Dedup: si ya existe ese hash, no guardamos una copia nueva.
    existing = db.query(Photo).filter(Photo.hash_sha256 == file_hash).first()
    if existing:
        tmp_path.unlink(missing_ok=True)
        return {
            "status": "already_exists",
            "id": existing.id,
            "hash_sha256": file_hash,
        }

    # 3. Foto nueva: la movemos a su ubicación final, organizada por
    #    año/mes de subida, con el hash como nombre de archivo.
    now = datetime.utcnow()
    dest_dir = storage_dir / f"{now.year:04d}" / f"{now.month:02d}"
    dest_dir.mkdir(parents=True, exist_ok=True)
    ext = Path(file.filename).suffix
    dest_path = dest_dir / f"{file_hash}{ext}"
    shutil.move(str(tmp_path), str(dest_path))

    parsed_taken_at = None
    if taken_at:
        try:
            parsed_taken_at = datetime.fromisoformat(taken_at)
        except ValueError:
            parsed_taken_at = None

    photo = Photo(
        hash_sha256=file_hash,
        filename=file.filename,
        filepath=str(dest_path),
        size_bytes=size_bytes,
        taken_at=parsed_taken_at,
        device_id=device_id,
    )
    db.add(photo)
    db.commit()
    db.refresh(photo)

    return {"id": photo.id, "hash_sha256": file_hash, "status": "stored"}


@app.get("/photos/manifest")
def get_manifest(token: str = Depends(verify_token), db: Session = Depends(get_db)):
    hashes = [row.hash_sha256 for row in db.query(Photo.hash_sha256).all()]
    return {"hashes": hashes}


@app.get("/photos/stats")
def get_stats(token: str = Depends(verify_token), db: Session = Depends(get_db)):
    total_photos = db.query(Photo).count()
    total_size_bytes = db.query(func.sum(Photo.size_bytes)).scalar() or 0
    return {"total_photos": total_photos, "total_size_bytes": total_size_bytes}
