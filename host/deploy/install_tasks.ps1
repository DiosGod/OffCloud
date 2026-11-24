# Registra las tareas programadas del server de producción. EJECUTAR COMO
# ADMINISTRADOR (una sola vez; volver a ejecutarlo las reemplaza):
#
#   powershell -ExecutionPolicy Bypass -File D:\OffCloud\server\deploy\install_tasks.ps1
#
# - "OffCloud Server": arranca el server al encender Windows, como SYSTEM (sin
#   iniciar sesión y sin ventana). Si el proceso termina con error, se
#   reintenta cada minuto.
# - "OffCloud Backup DB": copia app.db a <Root>\backups cada día a las 04:00
#   (si el PC estaba apagado, al encenderlo).

param([string]$Root = "D:\OffCloud")
$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw "Ejecuta este script en un PowerShell abierto como administrador." }

$server = Join-Path $Root "server"
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest

# --- Server ---
$action = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c `"$server\deploy\run_server.bat`"" -WorkingDirectory $server
$trigger = New-ScheduledTaskTrigger -AtStartup
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
