"use strict"

const os = require("os")
const config = require("./config")
const log = require("./logger")
const { VERSION, CAPABILITIES } = require("./capabilities")

/**
 * Cliente HTTP contra UcoBot.
 *
 * Todo sale del agente hacia afuera: no abre ningún puerto al mundo ni necesita
 * que el router tenga nada configurado. La PC del local puede estar detrás de
 * cualquier NAT y funciona igual.
 */

function url(ruta) {
  return `${config.load().serverUrl.replace(/\/$/, "")}${ruta}`
}

async function pedir(ruta, { method = "POST", body, auth = true, timeoutMs = 15000 } = {}) {
  const headers = { "Content-Type": "application/json" }
  if (auth) headers.Authorization = `Bearer ${config.load().token}`

  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), timeoutMs)

  try {
    const res = await fetch(url(ruta), {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: ctrl.signal,
    })

    const texto = await res.text()
    let datos = null
    try {
      datos = texto ? JSON.parse(texto) : null
    } catch {
      datos = { raw: texto }
    }

    if (!res.ok) {
      const err = new Error((datos && datos.error) || `HTTP ${res.status}`)
      err.status = res.status
      throw err
    }
    return datos
  } finally {
    clearTimeout(t)
  }
}

/** Canjea el código de vinculación por el token definitivo. */
async function pair(code, devices) {
  const datos = await pedir("/api/agent/pair", {
    auth: false,
    body: {
      code,
      hostname: os.hostname(),
      platform: process.platform,
      version: VERSION,
      capabilities: CAPABILITIES,
      devices,
    },
  })

  config.save({
    agentId: datos.agent_id,
    token: datos.token,
    name: datos.name,
    realtime: datos.realtime,
    settings: datos.settings || {},
  })

  log.info("Vinculado como", datos.name, `(${datos.agent_id})`)
  return datos
}

/**
 * Latido. Además de decir "estoy vivo", es por donde bajan los cambios de config
 * que el dueño hizo en el dashboard y sube el inventario de impresoras.
 */
async function heartbeat(devices) {
  const datos = await pedir("/api/agent/heartbeat", {
    body: {
      version: VERSION,
      platform: process.platform,
      hostname: os.hostname(),
      capabilities: CAPABILITIES,
      devices,
    },
  })
  if (datos && datos.settings) config.save({ settings: datos.settings })
  return datos
}

/** Reclama trabajos pendientes. Devuelve [] cuando no hay nada. */
async function claimJobs(limit = 5) {
  const datos = await pedir("/api/agent/jobs/next", {
    body: { capabilities: CAPABILITIES, limit },
  })
  return (datos && datos.jobs) || []
}

/** Informa cómo salió un trabajo. */
async function reportResult(jobId, ok, extra = {}) {
  return pedir(`/api/agent/jobs/${jobId}/result`, {
    body: ok ? { ok: true, result: extra } : { ok: false, error: extra.error || "Error" },
  })
}

module.exports = { pair, heartbeat, claimJobs, reportResult }
