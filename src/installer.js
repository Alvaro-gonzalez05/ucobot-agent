"use strict"

const fs = require("fs")
const os = require("os")
const path = require("path")
const { spawn, execFileSync } = require("child_process")
const log = require("./logger")

/**
 * El .exe se instala solo.
 *
 * POR QUÉ: la versión anterior traía un install.ps1 y había que hacerle botón
 * derecho → "Ejecutar con PowerShell". Para el dueño de un local eso es un paso
 * de más y una decisión que no entiende, y Windows encima suele bloquear los
 * scripts bajados de internet con un cartel rojo que asusta. Un ejecutable que se
 * copia solo, se agenda para arrancar con la máquina y abre su pantalla es lo que
 * cualquiera espera al hacer doble click.
 *
 * Qué hace la primera vez que se corre desde cualquier carpeta (Descargas, un
 * pendrive, el escritorio):
 *   1. se copia a %LOCALAPPDATA%\UcoBot
 *   2. deja un acceso directo en Inicio (arranca con Windows) y otro en el escritorio
 *   3. lanza la copia instalada, suelta de esta consola
 *   4. se va
 *
 * Sin pedir permisos de administrador: todo pasa dentro de la carpeta del usuario.
 */

const CARPETA = path.join(
  process.env.LOCALAPPDATA || path.join(os.homedir(), "AppData", "Local"),
  "UcoBot"
)
const EXE_INSTALADO = path.join(CARPETA, "UcoBotAgent.exe")

const NOMBRE_INICIO = "UcoBot Agent.lnk"
const NOMBRE_ESCRITORIO = "UcoBot Agent.lnk"

/** ¿Estamos corriendo desde afuera de la carpeta de instalación? */
function necesitaInstalarse() {
  if (process.platform !== "win32") return false
  if (!process.pkg) return false // corriendo desde el código fuente, no tocar nada
  if (process.argv.includes("--no-install")) return false
  return path.resolve(process.execPath).toLowerCase() !== EXE_INSTALADO.toLowerCase()
}

/** Corre un pedacito de PowerShell y devuelve su salida. */
function powershell(script) {
  return execFileSync(
    "powershell",
    ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
    { timeout: 30000, windowsHide: true, encoding: "utf8" }
  )
}

/**
 * Los accesos directos se crean con PowerShell porque un .lnk es un formato
 * binario de Windows: escribirlo a mano desde Node significaría implementar la
 * especificación entera, mientras que WScript.Shell ya viene instalado.
 */
function crearAccesoDirecto(carpeta, nombre, argumentos, descripcion) {
  const destino = path.join(carpeta, nombre)
  powershell(
    `$s = New-Object -ComObject WScript.Shell; ` +
      `$l = $s.CreateShortcut('${destino.replace(/'/g, "''")}'); ` +
      `$l.TargetPath = '${EXE_INSTALADO.replace(/'/g, "''")}'; ` +
      `$l.Arguments = '${argumentos}'; ` +
      `$l.WorkingDirectory = '${CARPETA.replace(/'/g, "''")}'; ` +
      `$l.Description = '${descripcion}'; ` +
      `$l.WindowStyle = 7; ` +
      `$l.Save()`
  )
  return destino
}

function carpetaEspecial(nombre) {
  return powershell(`[Environment]::GetFolderPath('${nombre}')`).trim()
}

/** Baja cualquier agente instalado de antes: el archivo queda tomado si vive. */
function cerrarInstanciaPrevia() {
  try {
    powershell(
      `Get-Process -Name 'UcoBotAgent' -ErrorAction SilentlyContinue | ` +
        `Where-Object { $_.Id -ne ${process.pid} } | Stop-Process -Force`
    )
  } catch {
    // No había ninguno, o no se pudo: se intenta copiar igual y si falla se avisa.
  }
}

/**
 * Instala y devuelve true si hay que salir (porque ya se lanzó la copia buena).
 */
function instalar() {
  console.log("")
  console.log("  UcoBot Agent")
  console.log("  Instalando en esta computadora...")
  console.log("")

  try {
    fs.mkdirSync(CARPETA, { recursive: true })
    cerrarInstanciaPrevia()

    // Pequeña espera: Windows tarda un instante en soltar el archivo del proceso
    // que acabamos de matar, y copiar encima fallaría con "acceso denegado".
    const hasta = Date.now() + 3000
    let copiado = false
    while (Date.now() < hasta && !copiado) {
      try {
        fs.copyFileSync(process.execPath, EXE_INSTALADO)
        copiado = true
      } catch {
        try {
          execFileSync("powershell", ["-NoProfile", "-Command", "Start-Sleep -Milliseconds 300"], {
            windowsHide: true,
          })
        } catch {}
      }
    }
    if (!copiado) throw new Error("No se pudo copiar el programa (¿quedó abierto?)")

    console.log(`  ✓ Copiado a ${CARPETA}`)

    crearAccesoDirecto(
      carpetaEspecial("Startup"),
      NOMBRE_INICIO,
      "",
      "Impresion directa de tickets de UcoBot"
    )
    console.log("  ✓ Va a arrancar solo con Windows")

    crearAccesoDirecto(
      carpetaEspecial("Desktop"),
      NOMBRE_ESCRITORIO,
      "--show",
      "Ver el estado del agente y vincular esta PC"
    )
    console.log("  ✓ Acceso directo en el escritorio")

    // La copia instalada arranca suelta: esta consola se puede cerrar tranquila.
    // Con --silent (o sea, viniendo de una actualización automática) no se abre
    // la pantalla: esto pasa a media tarde y nadie quiere ver saltar una pestaña
    // en la caja mientras atiende.
    const silencioso = process.argv.includes("--silent")
    const args = silencioso ? ["--child", "--no-open"] : ["--child", "--show"]
    const hijo = spawn(EXE_INSTALADO, args, {
      detached: true,
      stdio: "ignore",
      windowsHide: true,
    })
    if (!hijo.pid) throw new Error("no se pudo iniciar el agente instalado")
    log.info(`Agente instalado lanzado (pid ${hijo.pid})`)
    hijo.unref()

    console.log("")
    console.log("  Listo. Se abre la pantalla para vincular esta PC con tu cuenta.")
    console.log("  Ya podés cerrar esta ventana.")
    console.log("")
    return true
  } catch (e) {
    console.log("")
    console.log(`  No se pudo instalar: ${e.message}`)
    console.log("  El agente va a funcionar igual mientras esta ventana siga abierta.")
    console.log("")
    log.error("Falló la instalación:", e.message)
    return false
  }
}

/** Desinstala: baja el proceso, borra accesos directos y la carpeta. */
function desinstalar() {
  try {
    powershell(
      `Get-Process -Name 'UcoBotAgent' -ErrorAction SilentlyContinue | ` +
        `Where-Object { $_.Id -ne ${process.pid} } | Stop-Process -Force; ` +
        `Remove-Item (Join-Path ([Environment]::GetFolderPath('Startup')) '${NOMBRE_INICIO}') -Force -ErrorAction SilentlyContinue; ` +
        `Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) '${NOMBRE_ESCRITORIO}') -Force -ErrorAction SilentlyContinue`
    )
    console.log("  UcoBot Agent desinstalado. La vinculación se conserva por si lo reinstalás.")
    return true
  } catch (e) {
    console.log(`  No se pudo desinstalar: ${e.message}`)
    return false
  }
}

module.exports = { necesitaInstalarse, instalar, desinstalar, EXE_INSTALADO, CARPETA }
