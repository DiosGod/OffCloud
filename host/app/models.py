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
