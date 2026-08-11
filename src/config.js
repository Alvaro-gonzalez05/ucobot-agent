"use strict"

const fs = require("fs")
const os = require("os")
const path = require("path")

/**
 * Config del agente en disco.
 *
 * Va en ProgramData y no en la carpeta del usuario porque el agente arranca con
 * Windows y puede correr antes de que alguien inicie sesión. Si ProgramData no
 * se puede escribir (instalación sin permisos), cae a la carpeta del usuario:
 * peor, pero funciona.
 */

const CARPETA =
  process.env.UCOBOT_AGENT_HOME ||
  (process.platform === "win32"
    ? path.join(process.env.ProgramData || "C:\\ProgramData", "UcoBot")
    : path.join(os.homedir(), ".ucobot"))

const ARCHIVO = path.join(CARPETA, "agent.json")

const DEFAULTS = {
  /** A dónde le habla el agente. Se puede pisar para probar contra local. */
  serverUrl: process.env.UCOBOT_SERVER_URL || "https://chatbot-sass-eight.vercel.app",
  agentId: null,
  token: null,
  realtime: null,
  name: null,
  /** Config que manda el dashboard; el agente sólo la lee. */
  settings: {},
}

function asegurarCarpeta(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true })
    return dir
  } catch {
    const alterno = path.join(os.homedir(), ".ucobot")
    fs.mkdirSync(alterno, { recursive: true })
    return alterno
  }
}

let rutaActual = null
let cache = null

function rutaConfig() {
  if (rutaActual) return rutaActual
  const dir = asegurarCarpeta(CARPETA)
  rutaActual = dir === CARPETA ? ARCHIVO : path.join(dir, "agent.json")
  return rutaActual
}

function load() {
  if (cache) return cache
  try {
    const crudo = fs.readFileSync(rutaConfig(), "utf8")
    cache = { ...DEFAULTS, ...JSON.parse(crudo) }
  } catch {
    cache = { ...DEFAULTS }
  }
  return cache
}

function save(patch) {
  cache = { ...load(), ...patch }
  // Escritura atómica: si se corta la luz en el medio, la config vieja sobrevive
  // en vez de quedar un JSON a medio escribir que deja al agente sin token.
  const tmp = `${rutaConfig()}.tmp`
  fs.writeFileSync(tmp, JSON.stringify(cache, null, 2), "utf8")
  fs.renameSync(tmp, rutaConfig())
  return cache
}

function isPaired() {
  const c = load()
  return !!(c.agentId && c.token)
}

/** Borra la vinculación (el dashboard revocó, o el usuario quiere re-parear). */
function unpair() {
  return save({ agentId: null, token: null, realtime: null })
}

function dataDir() {
  return path.dirname(rutaConfig())
}

module.exports = { load, save, isPaired, unpair, dataDir, configPath: rutaConfig }
