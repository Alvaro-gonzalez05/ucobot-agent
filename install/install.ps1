# ============================================================================
# Instalador del UcoBot Agent
#
# NO PIDE PERMISOS DE ADMINISTRADOR, y eso es a propósito: en la mayoría de los
# locales la PC de la caja tiene un usuario común y el dueño no sabe la clave del
# administrador. Todo va a la carpeta del usuario y el arranque automático se
# resuelve con un acceso directo en Inicio, que no necesita nada especial.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File install.ps1
# ============================================================================

$ErrorActionPreference = 'Stop'

$destino = Join-Path $env:LOCALAPPDATA 'UcoBot'
$exeOrigen = Join-Path $PSScriptRoot 'UcoBotAgent.exe'
$exeDestino = Join-Path $destino 'UcoBotAgent.exe'

Write-Host ''
Write-Host '  UcoBot Agent' -ForegroundColor Green
Write-Host '  Impresion directa de tickets' -ForegroundColor DarkGray
Write-Host ''

if (-not (Test-Path $exeOrigen)) {
  Write-Host '  No encuentro UcoBotAgent.exe al lado de este script.' -ForegroundColor Red
  Write-Host '  Descomprimi todo el ZIP en la misma carpeta y volve a ejecutarlo.'
  Read-Host '  Enter para salir'
  exit 1
}

# Si ya estaba corriendo hay que bajarlo: el archivo queda tomado y no se puede
# reemplazar mientras el proceso vive.
Get-Process -Name 'UcoBotAgent' -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host '  Cerrando la version anterior...' -ForegroundColor DarkGray
  try { $_.Kill(); $_.WaitForExit(5000) } catch {}
}

New-Item -ItemType Directory -Force -Path $destino | Out-Null
Copy-Item $exeOrigen $exeDestino -Force
Write-Host "  Instalado en $destino" -ForegroundColor DarkGray

# --- Arranque automatico -----------------------------------------------------
# El acceso directo en Inicio corre cuando el usuario inicia sesion, que en una
# caja es apenas se prende la maquina.
$shell = New-Object -ComObject WScript.Shell

$inicio = [Environment]::GetFolderPath('Startup')
$lnkInicio = $shell.CreateShortcut((Join-Path $inicio 'UcoBot Agent.lnk'))
$lnkInicio.TargetPath = $exeDestino
$lnkInicio.WorkingDirectory = $destino
$lnkInicio.Description = 'Impresion directa de tickets de UcoBot'
$lnkInicio.WindowStyle = 7   # minimizado
$lnkInicio.Save()
Write-Host '  Arranque automatico configurado' -ForegroundColor DarkGray

# Acceso directo en el escritorio para abrir la pantalla de configuracion.
$escritorio = [Environment]::GetFolderPath('Desktop')
$lnkConfig = $shell.CreateShortcut((Join-Path $escritorio 'Configurar impresora UcoBot.lnk'))
$lnkConfig.TargetPath = $exeDestino
$lnkConfig.Arguments = '--show'
$lnkConfig.WorkingDirectory = $destino
$lnkConfig.Description = 'Ver el estado y vincular esta PC con UcoBot'
$lnkConfig.Save()

# --- Arrancar ----------------------------------------------------------------
Start-Process -FilePath $exeDestino -WindowStyle Hidden
Start-Sleep -Seconds 2
Start-Process 'http://localhost:17845'

Write-Host ''
Write-Host '  Listo. Se abrio la pantalla para vincular esta PC.' -ForegroundColor Green
Write-Host '  El codigo se genera en UcoBot: menu del perfil -> Impresion directa.'
Write-Host ''
Read-Host '  Enter para cerrar'
