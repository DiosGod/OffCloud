# Registra las tareas programadas del server de producción y (re)arranca el
# server. EJECUTAR COMO ADMINISTRADOR. Volver a ejecutarlo reemplaza las tareas
# y reinicia el server: es lo que hay que hacer después de cada deploy.ps1.
#
#   powershell -ExecutionPolicy Bypass -File D:\OffCloud\server\deploy\install_tasks.ps1
#
# - "OffCloud Server": arranca el server al encender Windows, como SYSTEM (sin
#   iniciar sesión y sin ventana), y lo vuelve a intentar cada 5 minutos si no
#   está corriendo (p. ej. el disco USB no estaba conectado).
# - "OffCloud Backup DB": copia app.db a <Root>\backups cada día a las 04:00
#   (si el PC estaba apagado, al encenderlo).

param([string]$Root = "D:\OffCloud")
$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw "Ejecuta este script en un PowerShell abierto como administrador." }

$server = Join-Path $Root "server"

# Si ya estaba corriendo, pararlo (también sirve para reiniciar tras desplegar
# una versión nueva). Parar la tarea solo cierra el cmd: el python de uvicorn
# puede quedar vivo con el código viejo y el puerto ocupado, así que se cierra
# aparte.
Stop-ScheduledTask -TaskName "OffCloud Server" -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -match 'uvicorn app\.main:app' -and $_.CommandLine -match '--port 8000' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest

# --- Server ---
$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c `"$server\deploy\run_server.bat`"" -WorkingDirectory $server
# Al arrancar Windows y, desde ahí, cada 5 minutos sin fin. Si el server ya
# está corriendo no pasa nada (IgnoreNew). Si no (el disco USB no estaba al
# encender, se desconectó y el vigilante paró el server, o se cayó), se vuelve
# a lanzar. "Reiniciar si falla" no basta: no salta cuando el programa termina
# con error.
$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) `
    -RepetitionInterval (New-TimeSpan -Minutes 5)).Repetition
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "OffCloud Server" -Force -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal `
    -Description "Server de OffCloud (uvicorn en el puerto 8000). Log en $Root\logs\server.log" | Out-Null

# --- Backup diario de la BD ---
$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c `"`"$server\venv\Scripts\python.exe`" `"$server\deploy\backup_db.py`" `"$Root\backups`" >> `"$Root\logs\backup.log`" 2>&1`"" `
    -WorkingDirectory $server
$trigger = New-ScheduledTaskTrigger -Daily -At "04:00"
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
Register-ScheduledTask -TaskName "OffCloud Backup DB" -Force -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal `
    -Description "Copia diaria de app.db a $Root\backups (conserva 30)" | Out-Null

Start-ScheduledTask -TaskName "OffCloud Server"
Get-ScheduledTask -TaskName "OffCloud*" | Select-Object TaskName, State | Format-Table
# El primer arranque puede tardar unos segundos: se reintenta durante 30 s.
$ok = $false
for ($i = 0; $i -lt 15 -and -not $ok; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-RestMethod -Uri "http://localhost:8000/health" -TimeoutSec 5
        Write-Host "Server en línea: $($r.status)"
        $ok = $true
    } catch {}
}
if (-not $ok) { Write-Warning "El server no responde tras 30 s; revisa $Root\logs\server.log" }
