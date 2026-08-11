"use strict"

const fs = require("fs")
const path = require("path")
const { execFile } = require("child_process")
const { dataDir } = require("../config")
const log = require("../logger")

/**
 * Inventario de impresoras de la máquina.
 *
 * Sube en cada latido y es lo que llena el desplegable del dashboard: el dueño
 * elige la impresora desde la web sin tener que tocar la PC del local.
 *
 * DOS CAMINOS, Y EL SEGUNDO IMPORTA MÁS DE LO QUE PARECE
 * Lo normal es preguntarle a WMI (Win32_Printer), que trae nombre, estado y cuál
 * es la predeterminada. Pero WMI depende del servicio de cola de impresión, y en
 * una PC de local ese servicio se cuelga o queda detenido más seguido de lo que
 * uno quisiera: cuando pasa, TODA consulta de impresoras falla con un "error
 * genérico" que no le dice nada a nadie. Por eso hay un segundo camino que lee
 * las impresoras del registro de Windows, que sigue respondiendo con el servicio
 * caído.
 *
 * Además se reporta el estado del servicio: si está detenido, el dueño ve el
 * motivo real en el dashboard en vez de "no imprime".
 */

const SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue'
$out = [ordered]@{ printers = @(); spooler = 'unknown'; source = 'none' }

try { $out.spooler = (Get-Service Spooler).Status.ToString() } catch {}

# Camino 1: WMI. Trae todo, pero necesita la cola de impresión viva.
$wmi = $null
try { $wmi = Get-CimInstance Win32_Printer -ErrorAction Stop } catch {}

if ($wmi) {
  $out.source = 'wmi'
  $out.printers = @($wmi | ForEach-Object {
    [ordered]@{
      name = $_.Name
      isDefault = [bool]$_.Default
      offline = [bool]$_.WorkOffline
      port = $_.PortName
    }
  })
} else {
  # Camino 2: el registro. Sobrevive al servicio caído.
  $def = ''
  try {
    $def = ((Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Windows' -Name Device).Device -split ',')[0]
  } catch {}

  $dev = $null
  try { $dev = Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Devices' } catch {}

  if ($dev) {
    $out.source = 'registry'
    $out.printers = @($dev.PSObject.Properties |
      Where-Object { $_.Name -notlike 'PS*' } |
      ForEach-Object {
        [ordered]@{
          name = $_.Name
          isDefault = ($_.Name -eq $def)
          offline = $false
          port = (($_.Value -split ',')[1])
        }
      })
  }
}

$out | ConvertTo-Json -Depth 4 -Compress
`

let rutaScript = null

function asegurarScript() {
  if (rutaScript && fs.existsSync(rutaScript)) return rutaScript
  const destino = path.join(dataDir(), "list-printers.ps1")
  fs.writeFileSync(destino, SCRIPT, "utf8")
  rutaScript = destino
  return destino
}

/** Estado de la última consulta, para mostrarlo en la pantalla local. */
let ultimoEstado = { spooler: "unknown", source: "none" }

function listPrinters() {
  if (process.platform !== "win32") return Promise.resolve([])

  return new Promise((resolve) => {
    execFile(
      "powershell",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", asegurarScript()],
      { timeout: 25000, windowsHide: true },
      (err, stdout) => {
        if (err && !stdout) {
          log.warn("No se pudo consultar las impresoras:", err.message)
          return resolve([])
        }
        try {
          const datos = JSON.parse(stdout)
          const crudo = datos.printers || []
          const filas = Array.isArray(crudo) ? crudo : [crudo]

          ultimoEstado = { spooler: datos.spooler, source: datos.source }
          if (datos.spooler && datos.spooler !== "Running") {
            log.warn(
              `La cola de impresión de Windows está en "${datos.spooler}". ` +
                "Hasta que arranque, la impresora no va a responder."
            )
          }

          resolve(
            filas
              .filter((f) => f && f.name)
              .map((f) => ({
                name: f.name,
                kind: "system",
                isDefault: !!f.isDefault,
                status: f.offline ? "offline" : "ready",
              }))
          )
        } catch (e) {
          log.warn("Respuesta inesperada al listar impresoras:", e.message)
          resolve([])
        }
      }
    )
  })
}

/** Diagnóstico de la última consulta (estado del servicio y de dónde salió la lista). */
function printerHealth() {
  return ultimoEstado
}

module.exports = { listPrinters, printerHealth }
