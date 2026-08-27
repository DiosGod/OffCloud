@echo off
cd /d "%~dp0"

echo Activando entorno virtual...
call venv\Scripts\activate.bat

echo.
echo Iniciando OffCloud server en 0.0.0.0:8000 ...
echo (Cierra esta ventana o pulsa Ctrl+C para detenerlo)
echo.

uvicorn app.main:app --host 0.0.0.0 --port 8000

REM Si el server termina o crashea, la ventana se queda abierta
REM para que puedas ver el error en vez de que se cierre sola.
pause
