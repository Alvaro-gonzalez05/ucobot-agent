# Desinstala el UcoBot Agent de esta PC.
# Deja la configuracion (ProgramData\UcoBot) por si se reinstala: asi no hay que
# volver a vincular. Para borrar todo, pasar -Todo.

param([switch]$Todo)

$ErrorActionPreference = 'SilentlyContinue'

Get-Process -Name 'UcoBotAgent' | ForEach-Object { $_.Kill() }
Start-Sleep -Milliseconds 500

Remove-Item (Join-Path ([Environment]::GetFolderPath('Startup')) 'UcoBot Agent.lnk') -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Configurar impresora UcoBot.lnk') -Force
Remove-Item (Join-Path $env:LOCALAPPDATA 'UcoBot') -Recurse -Force

if ($Todo) {
  Remove-Item (Join-Path $env:ProgramData 'UcoBot') -Recurse -Force
  Write-Host '  Se borro tambien la vinculacion.' -ForegroundColor DarkGray
}

Write-Host '  UcoBot Agent desinstalado.' -ForegroundColor Green
Read-Host '  Enter para cerrar'
