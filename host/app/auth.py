import secrets

from fastapi import Header, HTTPException

from .config import settings


def verify_credentials(username: str, password: str) -> bool:
    return username == settings.auth_username and password == settings.auth_password


def verify_token(authorization: str | None = Header(default=None)) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="Missing Authorization header")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(token, settings.api_token):
        raise HTTPException(status_code=401, detail="Invalid or missing token")
    return token
