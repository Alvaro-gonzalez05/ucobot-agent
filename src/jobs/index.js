"use strict"

const os = require("os")
const { printRaw } = require("./print-raw")
const { listPrinters } = require("../printers/list")
const { printRawWindows } = require("../printers/windows-raw")
const { printRawTcp, esDestinoDeRed } = require("../printers/tcp")
const config = require("../config")
const { VERSION, CAPABILITIES } = require("../capabilities")

/**
 * Registro de handlers.
 *
 * ACÁ SE EXTIENDE EL AGENTE. Cada capacidad nueva es una entrada en este objeto:
 * una función que recibe el payload y devuelve un resultado (o tira un Error con
 * un mensaje que el dueño pueda entender, porque ese texto termina en un toast
 * del dashboard).
 *
 * Ideas que ya encajan sin cambiar nada de la arquitectura: leer una balanza por
 * puerto serie, exportar un backup de la caja a un archivo, controlar un display
 * de cliente, mandar un comando a un lector de código de barras. Todo es
 * "trabajo que hay que hacer en la máquina del local", que es exactamente para lo
 * que existe esta cola.
 */

/** Pulso a la gaveta: ESC p 0 25 250, el estándar de facto. */
const PULSO_GAVETA = Buffer.from([0x1b, 0x70, 0x00, 0x19, 0xfa])

const handlers = {
  "print.raw": printRaw,

  "printer.list": async () => ({ printers: await listPrinters() }),

  "cashdrawer.open": async (payload) => {
    const impresora =
      (payload && payload.printer) || (config.load().settings || {}).defaultPrinter
    if (!impresora) throw new Error("No hay impresora configurada para abrir la gaveta")
    // La gaveta se abre por el conector RJ11 de la impresora, no por la PC: por
    // eso el pulso viaja como si fuera un trabajo de impresión vacío.
    return esDestinoDeRed(impresora)
      ? printRawTcp(impresora, PULSO_GAVETA)
      : printRawWindows(impresora, PULSO_GAVETA)
  },

  "agent.ping": async () => ({
    version: VERSION,
    capabilities: CAPABILITIES,
    hostname: os.hostname(),
    platform: process.platform,
    uptime: Math.round(process.uptime()),
  }),
}

module.exports = { handlers }
