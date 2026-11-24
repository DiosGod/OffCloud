# Instala o actualiza el server de producción a partir de una versión (tag) del repo.
#
#   powershell -ExecutionPolicy Bypass -File host\deploy\deploy.ps1 -Version v0.1.0
#
# Crea (si no existen) <Root>\server, \photos, \db, \logs y \backups, copia
# host/ de esa versión a <Root>\server, crea el venv e instala requirements.
# Conserva <Root>\server\.env y el venv entre actualizaciones.
# No toca photos\, db\ ni backups\.
#
# No necesita admin. Si el server ya está corriendo como tarea programada,
# reinícialo después (como admin):
#   Stop-ScheduledTask "OffCloud Server"; Start-ScheduledTask "OffCloud Server"

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Root = "D:\OffCloud"
)
$ErrorActionPreference = "Stop"

$repo = (git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
git -C $repo rev-parse --verify --quiet "$Version^{commit}" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "No existe la versión '$Version' en el repo" }

$server = Join-Path $Root "server"
foreach ($d in @($server, "$Root\photos", "$Root\db", "$Root\logs", "$Root\backups")) {
    New-Item -ItemType Directory -Force $d | Out-Null
}

# Código: se reemplaza entero (así no quedan archivos de versiones anteriores).
foreach ($d in @("app", "deploy")) {
    $p = Join-Path $server $d
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
$tar = Join-Path $env:TEMP "offcloud-$Version.tar"
git -C $repo archive --format=tar -o $tar "${Version}:host"
if ($LASTEXITCODE -ne 0) { throw "git archive falló" }
tar -xf $tar -C $server
Remove-Item $tar
Set-Content -Path (Join-Path $server "VERSION") -Value $Version -Encoding ascii

# venv propio del server.
$py = Join-Path $server "venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
    Write-Host "Creando venv..."
    python -m venv (Join-Path $server "venv")
}
& $py -m pip install --quiet --disable-pip-version-check -r (Join-Path $server "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "pip install falló" }

if (-not (Test-Path (Join-Path $server ".env"))) {
    Write-Warning "Falta $server\.env: créalo antes de arrancar el server (ver host\README.md)."
}
Write-Host "Desplegado $Version en $server"
