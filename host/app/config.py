from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    auth_username: str
    auth_password: str
    api_token: str
    storage_dir: str = "./data/photos"
    db_path: str = "./data/app.db"

    class Config:
        env_file = ".env"


settings = Settings()
