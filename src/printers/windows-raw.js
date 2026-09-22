"use strict"

const fs = require("fs")
const os = require("os")
const path = require("path")
const { execFile, spawn } = require("child_process")
const { dataDir } = require("../config")

/**
 * Manda bytes crudos a una impresora de Windows, salteando el driver gráfico.
 *
 * POR QUÉ POWERSHELL Y NO UN MÓDULO NATIVO
 * Imprimir RAW en Windows es una llamada a winspool.drv. Los paquetes de npm que
 * lo hacen (node-printer y derivados) necesitan node-gyp, Visual Studio Build
 * Tools y compilar en la máquina del cliente — inaceptable para algo que tiene
 * que instalarse con doble click. PowerShell con Add-Type hace exactamente lo
 * mismo, viene con Windows y deja el ejecutable sin una sola dependencia nativa.
 *
 * "RAW" es la parte importante: le dice al spooler que pase los bytes tal cual a
 * la impresora. Sin eso, Windows los interpretaría como un documento a renderizar
 * y saldría una hoja con caracteres raros en vez de un ticket.
 *
 * POR QUÉ UN POWERSHELL QUE QUEDA ABIERTO
 * Abrir PowerShell y compilar la clase de C# con Add-Type cuesta entre medio
 * segundo y varios segundos según la PC, y antes se pagaba en CADA ticket: en
 * las cajas viejas de un local eso era casi todo lo que tardaba en salir. Ahora
 * se abre uno solo al arrancar, compila una vez y queda esperando trabajos por
 * la entrada estándar; cada ticket cuesta lo que tarda el spooler. Si ese
 * proceso se cae o no arranca, se vuelve al método de antes (uno por ticket):
 * más lento, pero imprime.
 */

const COMPILAR = `
$ErrorActionPreference = 'Stop'
$code = @'
using System;
using System.Runtime.InteropServices;

public class UcoBotRawPrinter {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  public class DOCINFOW {
    [MarshalAs(UnmanagedType.LPWStr)] public string pDocName;
    [MarshalAs(UnmanagedType.LPWStr)] public string pOutputFile;
    [MarshalAs(UnmanagedType.LPWStr)] public string pDataType;
  }

  [DllImport("winspool.Drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
  public static extern bool OpenPrinter(string src, out IntPtr hPrinter, IntPtr pd);
  [DllImport("winspool.Drv", EntryPoint = "ClosePrinter", SetLastError = true)]
  public static extern bool ClosePrinter(IntPtr hPrinter);
  [DllImport("winspool.Drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
  public static extern bool StartDocPrinter(IntPtr hPrinter, int level, [In, MarshalAs(UnmanagedType.LPStruct)] DOCINFOW di);
  [DllImport("winspool.Drv", EntryPoint = "EndDocPrinter", SetLastError = true)]
  public static extern bool EndDocPrinter(IntPtr hPrinter);
  [DllImport("winspool.Drv", EntryPoint = "StartPagePrinter", SetLastError = true)]
  public static extern bool StartPagePrinter(IntPtr hPrinter);
  [DllImport("winspool.Drv", EntryPoint = "EndPagePrinter", SetLastError = true)]
  public static extern bool EndPagePrinter(IntPtr hPrinter);
  [DllImport("winspool.Drv", EntryPoint = "WritePrinter", SetLastError = true)]
  public static extern bool WritePrinter(IntPtr hPrinter, IntPtr pBytes, int dwCount, out int dwWritten);

  public static void Send(string printerName, byte[] bytes) {
    IntPtr h;
    int written = 0;
    if (!OpenPrinter(printerName, out h, IntPtr.Zero))
      throw new Exception("No se pudo abrir la impresora '" + printerName + "' (error " + Marshal.GetLastWin32Error() + ")");
    try {
      DOCINFOW di = new DOCINFOW();
      di.pDocName = "UcoBot";
      di.pDataType = "RAW";
      if (!StartDocPrinter(h, 1, di)) throw new Exception("StartDocPrinter falló (" + Marshal.GetLastWin32Error() + ")");
      try {
        if (!StartPagePrinter(h)) throw new Exception("StartPagePrinter falló (" + Marshal.GetLastWin32Error() + ")");
        IntPtr p = Marshal.AllocCoTaskMem(bytes.Length);
        try {
          Marshal.Copy(bytes, 0, p, bytes.Length);
          if (!WritePrinter(h, p, bytes.Length, out written))
            throw new Exception("WritePrinter falló (" + Marshal.GetLastWin32Error() + ")");
        } finally {
          Marshal.FreeCoTaskMem(p);
        }
        EndPagePrinter(h);
      } finally {
        EndDocPrinter(h);
      }
    } finally {
      ClosePrinter(h);
    }
  }
}
'@

Add-Type -TypeDefinition $code -Language CSharp
`

/** Un ticket por proceso: el método de siempre, y la red si el otro falla. */
const SCRIPT = `${COMPILAR}
[UcoBotRawPrinter]::Send($env:UCOBOT_PRINTER, [System.IO.File]::ReadAllBytes($env:UCOBOT_PAYLOAD))
Write-Output "OK"
`

/**
 * El proceso que queda abierto. Protocolo de una línea por trabajo:
 *   entra  "<id>|<impresora en base64>|<archivo en base64>"
 *   sale   "OK|<id>"  o  "ERR|<id>|<mensaje en base64>"
 * Todo lo que no es el id va en base64 (UTF-8): así un nombre de impresora o una
 * carpeta de usuario con tildes no se rompe en la consola, que en Windows no
 * habla UTF-8.
 */
const SERVIDOR = `${COMPILAR}
[Console]::Out.WriteLine("LISTO")
[Console]::Out.Flush()
while ($true) {
  $linea = [Console]::In.ReadLine()
  if ($linea -eq $null) { break }
  $p = $linea.Split('|')
  try {
    $impresora = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))
    $archivo = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[2]))
    [UcoBotRawPrinter]::Send($impresora, [System.IO.File]::ReadAllBytes($archivo))
    [Console]::Out.WriteLine("OK|" + $p[0])
  } catch {
    $e = $_.Exception
    while ($e.InnerException) { $e = $e.InnerException }
    $m = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($e.Message))
    [Console]::Out.WriteLine("ERR|" + $p[0] + "|" + $m)
  }
  [Console]::Out.Flush()
}
`

const ARGS = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"]
const TIEMPO_TRABAJO_MS = 30000
const TIEMPO_ARRANQUE_MS = 45000
/** Si el proceso no arrancó, se vuelve a intentar recién después de esto. */
const ESPERA_REINTENTO_MS = 60000

const rutas = {}

/** Deja el .ps1 en disco la primera vez; después se reusa. */
function asegurarScript(nombre = "raw-print.ps1", contenido = SCRIPT) {
  if (rutas[nombre] && fs.existsSync(rutas[nombre])) return rutas[nombre]
  const destino = path.join(dataDir(), nombre)
  fs.writeFileSync(destino, contenido, "utf8")
  rutas[nombre] = destino
  return destino
}

// --- El proceso que queda abierto -------------------------------------------

let servidor = null
let falloAl = 0
let proximoId = 1

/** Lo arranca si no está; devuelve una promesa que se cumple cuando compiló. */
function arrancarServidor() {
  if (servidor) return servidor.listo
  if (Date.now() - falloAl < ESPERA_REINTENTO_MS) return Promise.reject(new Error("sin servidor"))

  const proc = spawn("powershell", [...ARGS, asegurarScript("raw-print-servidor.ps1", SERVIDOR)], {
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  })
  const estado = { proc, pendientes: new Map(), resto: "", errores: "" }
  servidor = estado

  const caer = (motivo) => {
    if (servidor === estado) servidor = null
    for (const { reject, timer } of estado.pendientes.values()) {
      clearTimeout(timer)
      reject(new Error(motivo))
    }
    estado.pendientes.clear()
    try {
      proc.kill()
    } catch {
      /* ya terminó */
    }
  }
  estado.caer = caer

  estado.listo = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      falloAl = Date.now()
      caer("La impresora tardó demasiado en prepararse")
      reject(new Error("no arrancó a tiempo"))
    }, TIEMPO_ARRANQUE_MS)

    proc.stdout.setEncoding("utf8")
    proc.stdout.on("data", (trozo) => {
      estado.resto += trozo
      let i
      while ((i = estado.resto.indexOf("\n")) >= 0) {
        const linea = estado.resto.slice(0, i).trim()
        estado.resto = estado.resto.slice(i + 1)
        if (linea === "LISTO") {
          clearTimeout(timer)
          estado.arrancado = true
          resolve()
          continue
        }
        const [tipo, id, mensaje] = linea.split("|")
        const pendiente = estado.pendientes.get(id)
        if (!pendiente) continue
        estado.pendientes.delete(id)
        clearTimeout(pendiente.timer)
        if (tipo === "OK") pendiente.resolve()
        else pendiente.reject(new Error(Buffer.from(mensaje || "", "base64").toString("utf8") || "Falló la impresión"))
      }
    })
    proc.stderr.setEncoding("utf8")
    proc.stderr.on("data", (t) => {
      estado.errores = (estado.errores + t).slice(-2000)
    })
    proc.on("error", (e) => {
      clearTimeout(timer)
      falloAl = Date.now()
      caer(e.message)
      reject(e)
    })
    proc.on("exit", () => {
      clearTimeout(timer)
      // Si se cerró antes de estar listo, no tiene sentido reintentarlo en cada
      // ticket: se espera un rato y mientras tanto imprime el método de antes.
      if (!estado.arrancado) falloAl = Date.now()
      const detalle = estado.errores.split("\n")[0].trim()
      caer(detalle || "El proceso de impresión se cerró")
      reject(new Error(detalle || "se cerró al arrancar"))
    })
  })
  // Nadie puede quedar colgado de un rechazo que no escucha.
  estado.listo.catch(() => {})
  return estado.listo
}

/**
 * Deja PowerShell abierto y compilado antes del primer ticket, para que ése
 * también salga rápido. Se llama al arrancar; en otro sistema no hace nada.
 */
function precalentarImpresion() {
  if (process.platform !== "win32") return
  arrancarServidor().catch(() => {})
}

function enviarAlServidor(printerName, archivo) {
  const estado = servidor
  return new Promise((resolve, reject) => {
    const id = String(proximoId++)
    const b64 = (s) => Buffer.from(s, "utf8").toString("base64")
    // Si el spooler se traba, el proceso queda inservible: se descarta entero y
    // el próximo ticket arranca uno nuevo.
    const timer = setTimeout(() => {
      estado.pendientes.delete(id)
      reject(new Error("La impresora no respondió a tiempo"))
      estado.caer("Se reinicia el proceso de impresión")
    }, TIEMPO_TRABAJO_MS)
    estado.pendientes.set(id, { resolve, reject, timer })
    estado.proc.stdin.write(`${id}|${b64(printerName)}|${b64(archivo)}\n`)
  })
}

/**
 * @param {string} printerName Nombre exacto de la impresora en Windows.
 * @param {Buffer} bytes Bytes ESC/POS ya armados por la web.
 */
async function printRawWindows(printerName, bytes) {
  if (!printerName) throw new Error("No hay impresora configurada")

  // Los bytes van por archivo temporal y no por stdin: pasarle binario a
  // PowerShell por la entrada estándar lo corrompe (se mete a interpretar
  // codificaciones) y el ticket sale con basura.
  const tmp = path.join(os.tmpdir(), `ucobot-${Date.now()}-${Math.random().toString(36).slice(2)}.bin`)
  try {
    fs.writeFileSync(tmp, bytes)
  } catch (e) {
    throw new Error(`No se pudo preparar el trabajo: ${e.message}`)
  }

  try {
    let listo = true
    try {
      await arrancarServidor()
    } catch {
      listo = false
    }
    // Sólo se cae al método viejo si el proceso ni siquiera arrancó. Si arrancó
    // y el ticket falló, el error es de la impresora y reintentar por otro lado
    // podría imprimirlo dos veces.
    if (listo && servidor) await enviarAlServidor(printerName, tmp)
    else await imprimirConProcesoPropio(printerName, tmp)
    return { bytes: bytes.length, printer: printerName }
  } finally {
    try {
      fs.unlinkSync(tmp)
    } catch {
      /* ya no está */
    }
  }
}

/** El método de antes: un PowerShell que compila, imprime y se cierra. */
function imprimirConProcesoPropio(printerName, tmp) {
  return new Promise((resolve, reject) => {
    execFile(
      "powershell",
      [...ARGS, asegurarScript()],
      {
        timeout: TIEMPO_TRABAJO_MS,
        windowsHide: true,
        env: { ...process.env, UCOBOT_PRINTER: printerName, UCOBOT_PAYLOAD: tmp },
      },
      (err, stdout, stderr) => {
        if (err) {
          const detalle = (stderr || err.message || "").split("\n")[0].trim()
          return reject(new Error(detalle || "Falló la impresión"))
        }
        resolve()
      }
    )
  })
}

module.exports = { printRawWindows, precalentarImpresion }
