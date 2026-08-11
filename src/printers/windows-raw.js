"use strict"

const fs = require("fs")
const os = require("os")
const path = require("path")
const { execFile } = require("child_process")
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
 */

const SCRIPT = `
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
[UcoBotRawPrinter]::Send($env:UCOBOT_PRINTER, [System.IO.File]::ReadAllBytes($env:UCOBOT_PAYLOAD))
Write-Output "OK"
`

let rutaScript = null

/** Deja el .ps1 en disco la primera vez; después se reusa. */
function asegurarScript() {
  if (rutaScript && fs.existsSync(rutaScript)) return rutaScript
  const destino = path.join(dataDir(), "raw-print.ps1")
  fs.writeFileSync(destino, SCRIPT, "utf8")
  rutaScript = destino
  return destino
}

/**
 * @param {string} printerName Nombre exacto de la impresora en Windows.
 * @param {Buffer} bytes Bytes ESC/POS ya armados por la web.
 */
function printRawWindows(printerName, bytes) {
  return new Promise((resolve, reject) => {
    if (!printerName) return reject(new Error("No hay impresora configurada"))

    // Los bytes van por archivo temporal y no por stdin: pasarle binario a
    // PowerShell por la entrada estándar lo corrompe (se mete a interpretar
    // codificaciones) y el ticket sale con basura.
    const tmp = path.join(os.tmpdir(), `ucobot-${Date.now()}-${Math.random().toString(36).slice(2)}.bin`)

    try {
      fs.writeFileSync(tmp, bytes)
    } catch (e) {
      return reject(new Error(`No se pudo preparar el trabajo: ${e.message}`))
    }

    const limpiar = () => {
      try {
        fs.unlinkSync(tmp)
      } catch {
        /* ya no está */
      }
    }

    execFile(
      "powershell",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", asegurarScript()],
      {
        timeout: 30000,
        windowsHide: true,
        env: { ...process.env, UCOBOT_PRINTER: printerName, UCOBOT_PAYLOAD: tmp },
      },
      (err, stdout, stderr) => {
        limpiar()
        if (err) {
          const detalle = (stderr || err.message || "").split("\n")[0].trim()
          return reject(new Error(detalle || "Falló la impresión"))
        }
        resolve({ bytes: bytes.length, printer: printerName })
      }
    )
  })
}

module.exports = { printRawWindows }
