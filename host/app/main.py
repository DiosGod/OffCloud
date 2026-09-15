import hashlib
import shutil
import time
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session

from .auth import create_session, get_current_user, hash_password, require_admin, verify_password
from .config import settings
from .database import Base, SessionLocal, engine, get_db
from .models import Photo, User, UserSession

app = FastAPI(title="Photo Backup Server")

Base.metadata.create_all(bind=engine)

ADMIN_HTML_PATH = Path(__file__).parent / "static" / "admin.html"


@app.on_event("startup")
def bootstrap_admin() -> None:
    """Crea el primer usuario admin desde .env, solo si aún no hay ninguno."""
    db = SessionLocal()
    try:
        if db.query(User).count() == 0:
            if settings.initial_admin_username and settings.initial_admin_password:
                admin = User(
                    username=settings.initial_admin_username,
                    password_hash=hash_password(settings.initial_admin_password),
                    is_admin=True,
                )
                db.add(admin)
                db.commit()
    finally:
        db.close()

    port = settings.admin_display_port
    print("\n" + "=" * 60)
    print(f"  Panel admin:  http://localhost:{port}/admin")
    print(f"                http://<tu-ip-tailscale>:{port}/admin")
    print("=" * 60 + "\n")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/auth/login")
def login(username: str = Form(...), password: str = Form(...), db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == username).first()
    if not user or not verify_password(password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = create_session(db, user)
    return {"token": token, "is_admin": user.is_admin}


@app.post("/photos/upload")
def upload_photo(
    file: UploadFile = File(...),
    device_id: str | None = Form(default=None),
    taken_at: str | None = Form(default=None),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    storage_dir = Path(settings.storage_dir) / user.username
    tmp_dir = storage_dir / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    tmp_path = tmp_dir / f"upload_{int(time.time() * 1000)}"
    sha256 = hashlib.sha256()
    size_bytes = 0
    with tmp_path.open("wb") as buffer:
        while chunk := file.file.read(1024 * 1024):
            sha256.update(chunk)
            size_bytes += len(chunk)
            buffer.write(chunk)
    file_hash = sha256.hexdigest()

    existing = (
        db.query(Photo)
        .filter(Photo.owner_id == user.id, Photo.hash_sha256 == file_hash)
        .first()
    )
    if existing:
        tmp_path.unlink(missing_ok=True)
        return {"status": "already_exists", "id": existing.id, "hash_sha256": file_hash}

    parsed_taken_at = None
    if taken_at:
        try:
            parsed_taken_at = datetime.fromisoformat(taken_at)
        except ValueError:
            parsed_taken_at = None

    effective_date = parsed_taken_at or datetime.utcnow()
    dest_dir = storage_dir / f"{effective_date.year:04d}" / f"{effective_date.month:02d}"
    dest_dir.mkdir(parents=True, exist_ok=True)
    ext = Path(file.filename).suffix
    dest_path = dest_dir / f"{file_hash}{ext}"
    shutil.move(str(tmp_path), str(dest_path))

    photo = Photo(
        owner_id=user.id,
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
def get_manifest(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    hashes = [
        row.hash_sha256
        for row in db.query(Photo.hash_sha256).filter(Photo.owner_id == user.id).all()
    ]
    return {"hashes": hashes}


@app.get("/photos/stats")
def get_stats(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    total_photos = db.query(Photo).filter(Photo.owner_id == user.id).count()
    total_size_bytes = (
        db.query(func.sum(Photo.size_bytes)).filter(Photo.owner_id == user.id).scalar() or 0
    )
    return {"total_photos": total_photos, "total_size_bytes": total_size_bytes}


# --- Panel de administración ---

class CreateUserRequest(BaseModel):
    username: str
    password: str
    is_admin: bool = False


@app.get("/admin", response_class=HTMLResponse)
def admin_page():
    return ADMIN_HTML_PATH.read_text(encoding="utf-8")


@app.get("/admin/users")
def list_users(admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    users = db.query(User).all()
    result = []
    for u in users:
        photo_count = db.query(Photo).filter(Photo.owner_id == u.id).count()
        result.append(
            {
                "id": u.id,
                "username": u.username,
                "is_admin": u.is_admin,
                "photo_count": photo_count,
                "created_at": u.created_at.isoformat() if u.created_at else None,
            }
        )
    return result


@app.post("/admin/users", status_code=201)
def create_user(
    payload: CreateUserRequest,
    admin: User = Depends(require_admin),
    db: Session = Depends(get_db),
):
    existing = db.query(User).filter(User.username == payload.username).first()
    if existing:
        raise HTTPException(status_code=409, detail="Username already exists")

    user = User(
        username=payload.username,
        password_hash=hash_password(payload.password),
        is_admin=payload.is_admin,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return {"id": user.id, "username": user.username, "is_admin": user.is_admin}


@app.delete("/admin/users/{user_id}")
def delete_user(
    user_id: int, admin: User = Depends(require_admin), db: Session = Depends(get_db)
):
    if user_id == admin.id:
        raise HTTPException(status_code=400, detail="No puedes eliminar tu propio usuario")

    target = db.query(User).filter(User.id == user_id).first()
    if not target:
        raise HTTPException(status_code=404, detail="User not found")

    # Borrado en cascada: fotos (archivo + fila), sesiones, y por último el usuario.
    photos = db.query(Photo).filter(Photo.owner_id == target.id).all()
    for photo in photos:
        try:
            Path(photo.filepath).unlink(missing_ok=True)
        except Exception:
            pass
        db.delete(photo)

    # Limpia la carpeta del usuario si quedó vacía (o con solo subcarpetas vacías).
    user_dir = Path(settings.storage_dir) / target.username
    if user_dir.exists():
        shutil.rmtree(user_dir, ignore_errors=True)

    db.query(UserSession).filter(UserSession.user_id == target.id).delete()
    db.delete(target)
    db.commit()

    return {"status": "deleted", "deleted_photos": len(photos)}


@app.get("/admin/photos")
def list_all_photos(admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    photos = db.query(Photo).all()
    result = []
    for p in photos:
        owner = db.query(User).filter(User.id == p.owner_id).first()
        result.append(
            {
                "id": p.id,
                "owner_username": owner.username if owner else "??",
                "filename": p.filename,
                "size_bytes": p.size_bytes,
                "uploaded_at": p.uploaded_at.isoformat() if p.uploaded_at else None,
                "taken_at": p.taken_at.isoformat() if p.taken_at else None,
            }
        )
    return result


@app.delete("/admin/photos/{photo_id}")
def delete_photo(
    photo_id: int, admin: User = Depends(require_admin), db: Session = Depends(get_db)
):
    photo = db.query(Photo).filter(Photo.id == photo_id).first()
    if not photo:
        raise HTTPException(status_code=404, detail="Photo not found")

    try:
        Path(photo.filepath).unlink(missing_ok=True)
    except Exception:
        pass

    db.delete(photo)
    db.commit()
    return {"status": "deleted"}
