"use strict"

const fs = require("fs")
const path = require("path")
const { dataDir } = require("./config")

/**
 * Log a archivo, porque el agente corre sin consola.
 *
 * Cuando un cliente llama diciendo "no imprime", este archivo es la única forma
 * de saber qué pasó. Se lo puede ver desde la pantalla local del agente sin
 * pedirle a nadie que busque carpetas.
 */

const ARCHIVO = path.join(dataDir(), "agent.log")
const MAX_BYTES = 512 * 1024

/** Últimas líneas en memoria: es lo que muestra la pantalla local. */
const recientes = []
const MAX_RECIENTES = 300

function rotarSiHaceFalta() {
  try {
    if (fs.statSync(ARCHIVO).size > MAX_BYTES) {
      fs.renameSync(ARCHIVO, `${ARCHIVO}.1`)
    }
  } catch {
    // No existe todavía: nada que rotar.
  }
}

function escribir(nivel, args) {
  const linea = `[${new Date().toISOString()}] ${nivel} ${args
    .map((a) => (typeof a === "string" ? a : safeStringify(a)))
    .join(" ")}`

  recientes.push(linea)
  if (recientes.length > MAX_RECIENTES) recientes.shift()

  // eslint-disable-next-line no-console
  console.log(linea)

  try {
    rotarSiHaceFalta()
    fs.appendFileSync(ARCHIVO, `${linea}\n`, "utf8")
  } catch {
    // Sin disco o sin permisos: no vale la pena romper por un log.
  }
}

function safeStringify(v) {
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

module.exports = {
  info: (...a) => escribir("INFO", a),
  warn: (...a) => escribir("WARN", a),
  error: (...a) => escribir("ERROR", a),
  tail: () => recientes.slice(-120),
  logPath: ARCHIVO,
}
