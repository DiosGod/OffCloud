from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # Se usan SOLO la primera vez que arranca el server (si no hay
    # ningún usuario todavía) para crear la cuenta admin inicial.
    # Después de eso, los usuarios se gestionan desde /admin.
    initial_admin_username: str | None = None
    initial_admin_password: str | None = None

    storage_dir: str = "./data/photos"
    db_path: str = "./data/app.db"

    # Solo informativo: para mostrar la URL del panel admin en los logs
    # al arrancar. Si corres uvicorn en otro puerto, ajusta esto también.
    admin_display_port: int = 8000

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
