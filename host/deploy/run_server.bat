@echo off
REM Arranque del server en producción. Lo lanza la tarea programada
REM "OffCloud Server" (ver install_tasks.ps1) al encender Windows, como SYSTEM
REM y sin ventana. Para desarrollo usa start_server.bat.
REM
REM Estructura esperada (la crea deploy.ps1):
REM   <raíz>\server\deploy\run_server.bat   <- este archivo
REM   <raíz>\server\venv\
REM   <raíz>\logs\server.log                <- salida del server

cd /d "%~dp0.."
if not exist "..\logs" mkdir "..\logs"

set PYTHONUNBUFFERED=1
set PYTHONIOENCODING=utf-8

echo. >> "..\logs\server.log"
echo ===== %DATE% %TIME% arranque ===== >> "..\logs\server.log"
"venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 >> "..\logs\server.log" 2>&1
exit /b %ERRORLEVEL%
