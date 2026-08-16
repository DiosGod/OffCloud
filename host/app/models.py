from sqlalchemy import BigInteger, Column, DateTime, Integer, String
from sqlalchemy.sql import func

from .database import Base


class Photo(Base):
    __tablename__ = "photos"

    id = Column(Integer, primary_key=True, index=True)
    hash_sha256 = Column(String, unique=True, index=True, nullable=False)
    filename = Column(String, nullable=False)
    filepath = Column(String, nullable=False)
    size_bytes = Column(BigInteger, nullable=False)
    taken_at = Column(DateTime, nullable=True)
    uploaded_at = Column(DateTime, server_default=func.now())
    device_id = Column(String, nullable=True)
