"use strict"

const config = require("./config")
const log = require("./logger")

/**
 * El timbre: un WebSocket a Supabase Realtime que avisa "hay trabajo".
 *
 * POR QUÉ NO ALCANZA CON PREGUNTAR CADA TANTO
 * Un ticket tiene que salir en menos de un segundo desde que el cajero aprieta.
 * Preguntarle al servidor cada segundo son 86.400 requests por día por cada PC
 * instalada: caro y absurdo. El timbre invierte la carga — la conexión la
 * mantiene Supabase (no Vercel), no cuesta nada y avisa al instante.
 *
 * QUÉ VIAJA POR ACÁ: nada. El mensaje es literalmente {ring:1}. El trabajo real
 * lo va a buscar el agente por HTTPS con su token. Por eso el canal puede ser
 * público: aunque alguien adivinara el nombre (24 bytes al azar), lo único que
 * lograría es que el agente haga una consulta de más.
 *
 * Si el WebSocket se cae, no pasa nada grave: el poll de reserva del bucle
 * principal sigue trayendo los trabajos, sólo que con más demora.
 */

let cliente = null
let canal = null
let conectado = false
/** Por qué está caído, en palabras. Viaja en el latido y se ve en el panel. */
let ultimoError = null
/** Con qué datos se abrió, para notar si el servidor mandó otros. */
let firmaActual = null

function firma(rt) {
  return rt ? `${rt.url || ""}|${rt.anon_key || ""}|${rt.channel || ""}` : ""
}

async function connect(onRing, onStatus) {
  const cfg = config.load()
  if (!cfg.realtime || !cfg.realtime.url || !cfg.realtime.anon_key) {
    log.warn("Sin datos de Realtime: el agente va a funcionar por consulta periódica")
    ultimoError = "Sin datos de Realtime"
    firmaActual = firma(cfg.realtime)
    return false
  }
  firmaActual = firma(cfg.realtime)

  try {
    const { createClient } = require("@supabase/supabase-js")

    cliente = createClient(cfg.realtime.url, cfg.realtime.anon_key, {
      auth: { persistSession: false, autoRefreshToken: false },
      realtime: { params: { eventsPerSecond: 20 } },
    })

    canal = cliente
      .channel(cfg.realtime.channel, { config: { private: false } })
      .on("broadcast", { event: "job" }, () => onRing())
      .subscribe((estado, error) => {
        conectado = estado === "SUBSCRIBED"
        if (typeof onStatus === "function") onStatus(conectado)
        if (conectado) {
          ultimoError = null
          log.info("Timbre conectado")
        } else if (estado === "CHANNEL_ERROR" || estado === "TIMED_OUT" || estado === "CLOSED") {
          ultimoError = error ? String(error.message || error) : estado
          log.warn(`Timbre desconectado (${estado}); se usa la consulta de reserva${error ? `: ${error.message || error}` : ""}`)
        }
      })

    return true
  } catch (e) {
    log.warn("No se pudo conectar el timbre:", e.message)
    ultimoError = e.message
    return false
  }
}

function disconnect() {
  try {
    if (canal && cliente) cliente.removeChannel(canal)
  } catch {
    /* ya estaba cortado */
  }
  canal = null
  cliente = null
  conectado = false
  firmaActual = null
}

/** Lo que se reporta en el latido. */
function estado() {
  return { connected: conectado, error: ultimoError }
}

/** ¿Los datos guardados son distintos de los que usa la conexión actual? */
function datosCambiaron() {
  // Si nunca se intentó conectar no hay nada que "cambió": el arranque se
  // encarga. Sin este corte, el primer latido (que corre antes de conectar el
  // timbre) lo conectaba por su cuenta y quedaban dos suscripciones.
  if (firmaActual === null) return false
  return firma(config.load().realtime) !== firmaActual
}

module.exports = { connect, disconnect, estado, datosCambiaron }
