"use strict"

const config = require("../config")
const log = require("../logger")
const { printRawWindows } = require("../printers/windows-raw")
const { printRawTcp, esDestinoDeRed } = require("../printers/tcp")
const { listPrinters } = require("../printers/list")

/**
 * El trabajo principal: mandarle bytes a una impresora.
 *
 * El agente NO sabe qué es un ticket. Recibe bytes ya armados por la web y los
 * empuja. Es a propósito: así, cambiar el diseño del ticket es un deploy de la
 * web y no una reinstalación en la PC de cada cliente.
 */

/** Qué impresora usar: la del trabajo, la configurada, o la predeterminada. */
async function resolverImpresora(payload) {
  if (payload.printer) return payload.printer

  const desdeConfig = (config.load().settings || {}).defaultPrinter
  if (desdeConfig) return desdeConfig

  // Último recurso: la predeterminada de Windows. Sirve para el primer ticket,
  // antes de que el dueño haya elegido nada en el dashboard.
  const impresoras = await listPrinters()
  const predeterminada = impresoras.find((p) => p.isDefault)
  if (predeterminada) return predeterminada.name

  throw new Error("No hay ninguna impresora configurada en este equipo")
}

async function printRaw(payload) {
  if (!payload || !payload.data_b64) throw new Error("El trabajo vino sin contenido")

  const bytes = Buffer.from(payload.data_b64, "base64")
  const impresora = await resolverImpresora(payload)
  const copias = Math.min(Math.max(Number(payload.copies) || 1, 1), 5)

  log.info(`Imprimiendo ${payload.label || "trabajo"} (${bytes.length} bytes) en "${impresora}"`)

  let resultado = null
  for (let i = 0; i < copias; i++) {
    resultado = esDestinoDeRed(impresora)
      ? await printRawTcp(impresora, bytes)
      : await printRawWindows(impresora, bytes)
  }

  return { ...resultado, copies: copias }
}

module.exports = { printRaw }
