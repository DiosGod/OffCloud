from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.sql import func

from .database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True, nullable=False)
    password_hash = Column(String, nullable=False)
    is_admin = Column(Boolean, default=False, nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class UserSession(Base):
    """Token de sesión emitido en cada login. Reemplaza al token fijo único."""

    __tablename__ = "sessions"

    token = Column(String, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class Photo(Base):
    __tablename__ = "photos"
    __table_args__ = (
        UniqueConstraint("owner_id", "hash_sha256", name="uq_owner_hash"),
    )

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    hash_sha256 = Column(String, index=True, nullable=False)
    filename = Column(String, nullable=False)
    filepath = Column(String, nullable=False)
    size_bytes = Column(BigInteger, nullable=False)
    taken_at = Column(DateTime, nullable=True)
    uploaded_at = Column(DateTime, server_default=func.now())
    device_id = Column(String, nullable=True)


class Folder(Base):
    """Carpeta lógica en la nube. Se identifica por nombre dentro de cada
    usuario: dos dispositivos con la misma cuenta que sincronizan "Camera"
    comparten la misma carpeta. No afecta a dónde se guarda el archivo."""

    __tablename__ = "folders"
    __table_args__ = (UniqueConstraint("owner_id", "name", name="uq_owner_folder"),)

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    name = Column(String, nullable=False)
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now())


class FolderPhoto(Base):
    """Relación carpeta <-> foto (muchos a muchos: la misma foto, por hash,
    puede estar en varias carpetas)."""

    __tablename__ = "folder_photos"

    folder_id = Column(Integer, ForeignKey("folders.id"), primary_key=True)
    photo_id = Column(Integer, ForeignKey("photos.id"), primary_key=True)
