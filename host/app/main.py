import hashlib
import os
import re
import shutil
import uuid
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .auth import create_session, get_current_user, hash_password, require_admin, verify_password
from .config import settings
from .database import Base, SessionLocal, engine, get_db
from .models import Folder, FolderPhoto, Photo, User, UserSession

app = FastAPI(title="Photo Backup Server")

Base.metadata.create_all(bind=engine)

ADMIN_HTML_PATH = Path(__file__).parent / "static" / "admin.html"

# El nombre de usuario se usa como nombre de carpeta en disco
# (STORAGE_DIR/{username}), así que solo se admiten nombres que sean una
# carpeta normal: letras, números, punto, guion y guion bajo, empezando por
# letra o número, sin acabar en punto y sin nombres reservados de Windows.
USERNAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")
WINDOWS_RESERVED = {"con", "prn", "aux", "nul"} | {f"com{i}" for i in range(1, 10)} | {
    f"lpt{i}" for i in range(1, 10)
}


def username_error(username: str) -> str | None:
    """Devuelve el motivo por el que el nombre no vale, o None si es válido."""
    if not USERNAME_RE.fullmatch(username):
        return (
            "Nombre de usuario no válido: 1-32 caracteres, solo letras, números, "
            "'.', '-' y '_', empezando por letra o número"
        )
    if username.endswith("."):
        return "Nombre de usuario no válido: no puede acabar en '.'"
    if username.split(".")[0].lower() in WINDOWS_RESERVED:
        return "Nombre de usuario no válido: es un nombre reservado de Windows"
    return None


def user_storage_dir(username: str) -> Path | None:
    """Carpeta física del usuario, solo si es una subcarpeta directa de STORAGE_DIR."""
    root = Path(settings.storage_dir).resolve()
    user_dir = (root / username).resolve()
    if username_error(username) or user_dir.parent != root:
        return None
    return user_dir


@app.on_event("startup")
def bootstrap_admin() -> None:
    """Crea el primer usuario admin desde .env, solo si aún no hay ninguno."""
    db = SessionLocal()
    try:
        if db.query(User).count() == 0:
            if settings.initial_admin_username and settings.initial_admin_password:
                error = username_error(settings.initial_admin_username)
                if error:
                    raise RuntimeError(f"INITIAL_ADMIN_USERNAME: {error}")
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
    folder_id: int | None = Form(default=None),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    folder = get_owned_folder(db, user, folder_id) if folder_id is not None else None

    storage_dir = Path(settings.storage_dir) / user.username
    tmp_dir = storage_dir / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # Nombre único por subida: con un nombre basado en la hora, dos subidas en
    # el mismo milisegundo escribían en el mismo archivo y se mezclaban.
    tmp_path = tmp_dir / f"upload_{uuid.uuid4().hex}"
    try:
        return _store_upload(db, user, folder, file, tmp_path, storage_dir, device_id, taken_at)
    finally:
        tmp_path.unlink(missing_ok=True)


def _find_existing(db: Session, user: User, file_hash: str) -> Photo | None:
    return (
        db.query(Photo)
        .filter(Photo.owner_id == user.id, Photo.hash_sha256 == file_hash)
        .first()
    )


def _already_exists(db: Session, folder: Folder | None, existing: Photo, file_hash: str) -> dict:
    if folder:
        link_photo_to_folder(db, folder, existing)
        db.commit()
    return {"status": "already_exists", "id": existing.id, "hash_sha256": file_hash}


def _store_upload(
    db: Session,
    user: User,
    folder: Folder | None,
    file: UploadFile,
    tmp_path: Path,
    storage_dir: Path,
    device_id: str | None,
    taken_at: str | None,
) -> dict:
    sha256 = hashlib.sha256()
    size_bytes = 0
    with tmp_path.open("wb") as buffer:
        while chunk := file.file.read(1024 * 1024):
            sha256.update(chunk)
            size_bytes += len(chunk)
            buffer.write(chunk)
    file_hash = sha256.hexdigest()

    existing = _find_existing(db, user, file_hash)
    if existing:
        return _already_exists(db, folder, existing, file_hash)

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
    # Mismo volumen (tmp está dentro de la carpeta del usuario): os.replace es
    # atómico. Si el destino ya existe es el mismo contenido (mismo hash).
    if not dest_path.exists():
        os.replace(tmp_path, dest_path)

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
    try:
        db.flush()
    except IntegrityError:
        # La misma foto llegó dos veces a la vez y la otra subida la registró
        # primero. El archivo en disco es idéntico (mismo hash), así que vale.
        db.rollback()
        existing = _find_existing(db, user, file_hash)
        if existing is None:
            raise
        return _already_exists(db, folder, existing, file_hash)
    if folder:
        link_photo_to_folder(db, folder, photo)
    db.commit()
    db.refresh(photo)

    return {"id": photo.id, "hash_sha256": file_hash, "status": "stored"}


@app.get("/photos/{photo_id}/file")
def download_photo(
    photo_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)
):
    photo = db.query(Photo).filter(Photo.id == photo_id, Photo.owner_id == user.id).first()
    if not photo or not Path(photo.filepath).exists():
        raise HTTPException(status_code=404, detail="Photo not found")
    return FileResponse(photo.filepath, filename=photo.filename)


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


# --- Carpetas (multi-dispositivo) ---

class CreateFolderRequest(BaseModel):
    name: str


class LinkHashesRequest(BaseModel):
    hashes: list[str]


def get_owned_folder(db: Session, user: User, folder_id: int) -> Folder:
    folder = db.query(Folder).filter(Folder.id == folder_id, Folder.owner_id == user.id).first()
    if not folder:
        raise HTTPException(status_code=404, detail="Folder not found")
    return folder


def link_photo_to_folder(db: Session, folder: Folder, photo: Photo) -> bool:
    """Añade la foto a la carpeta si no estaba. Devuelve True si la añadió."""
    exists = (
        db.query(FolderPhoto)
        .filter(FolderPhoto.folder_id == folder.id, FolderPhoto.photo_id == photo.id)
        .first()
    )
    if exists:
        return False
    db.add(FolderPhoto(folder_id=folder.id, photo_id=photo.id))
    folder.updated_at = datetime.utcnow()
    return True


def folder_to_dict(db: Session, folder: Folder) -> dict:
    photo_count, total_size = (
        db.query(func.count(Photo.id), func.coalesce(func.sum(Photo.size_bytes), 0))
        .join(FolderPhoto, FolderPhoto.photo_id == Photo.id)
        .filter(FolderPhoto.folder_id == folder.id)
        .one()
    )
    return {
        "id": folder.id,
        "name": folder.name,
        "photo_count": photo_count,
        "total_size_bytes": total_size,
        "updated_at": folder.updated_at.isoformat() if folder.updated_at else None,
    }


@app.get("/folders")
def list_folders(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    folders = db.query(Folder).filter(Folder.owner_id == user.id).order_by(Folder.name).all()
    return [folder_to_dict(db, f) for f in folders]


@app.post("/folders")
def create_folder(
    payload: CreateFolderRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Get-or-create por nombre: si otro dispositivo ya creó una carpeta con
    ese nombre, se devuelve la misma en vez de duplicarla."""
    name = payload.name.strip()
    if not name:
        raise HTTPException(status_code=400, detail="Folder name required")
    folder = db.query(Folder).filter(Folder.owner_id == user.id, Folder.name == name).first()
    if not folder:
        folder = Folder(owner_id=user.id, name=name)
        db.add(folder)
        db.commit()
        db.refresh(folder)
    return folder_to_dict(db, folder)


@app.get("/folders/{folder_id}/manifest")
def get_folder_manifest(
    folder_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)
):
    folder = get_owned_folder(db, user, folder_id)
    photos = (
        db.query(Photo)
        .join(FolderPhoto, FolderPhoto.photo_id == Photo.id)
        .filter(FolderPhoto.folder_id == folder.id)
        .all()
    )
    return {
        "photos": [
            {
                "id": p.id,
                "hash_sha256": p.hash_sha256,
                "filename": p.filename,
                "size_bytes": p.size_bytes,
                "taken_at": p.taken_at.isoformat() if p.taken_at else None,
            }
            for p in photos
        ]
    }


@app.post("/folders/{folder_id}/link")
def link_hashes_to_folder(
    folder_id: int,
    payload: LinkHashesRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Mete en la carpeta fotos que el usuario YA tiene en el server (por
    hash), sin volver a subir los bytes. Devuelve los hashes que el server
    no tiene: esos sí hay que subirlos."""
    folder = get_owned_folder(db, user, folder_id)
    wanted = set(payload.hashes)
    photos = (
        db.query(Photo)
        .filter(Photo.owner_id == user.id, Photo.hash_sha256.in_(wanted))
        .all()
        if wanted
        else []
    )
    linked = sum(1 for p in photos if link_photo_to_folder(db, folder, p))
    db.commit()
    found = {p.hash_sha256 for p in photos}
    return {"linked": linked, "missing": sorted(wanted - found)}


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
    error = username_error(payload.username)
    if error:
        raise HTTPException(status_code=400, detail=error)

    # En Windows "Ana" y "ana" serían la misma carpeta: se comparan sin mayúsculas.
    existing = (
        db.query(User).filter(func.lower(User.username) == payload.username.lower()).first()
    )
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

    # Limpia la carpeta del usuario (ya sin fotos). Solo si es una subcarpeta
    # directa de STORAGE_DIR: un nombre raro nunca puede borrar nada fuera.
    user_dir = user_storage_dir(target.username)
    if user_dir and user_dir.exists():
        shutil.rmtree(user_dir, ignore_errors=True)

    folder_ids = [f.id for f in db.query(Folder.id).filter(Folder.owner_id == target.id).all()]
    if folder_ids:
        db.query(FolderPhoto).filter(FolderPhoto.folder_id.in_(folder_ids)).delete(
            synchronize_session=False
        )
        db.query(Folder).filter(Folder.owner_id == target.id).delete()

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

    db.query(FolderPhoto).filter(FolderPhoto.photo_id == photo.id).delete()
    db.delete(photo)
    db.commit()
    return {"status": "deleted"}
