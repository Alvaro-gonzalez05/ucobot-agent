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

async function connect(onRing) {
  const cfg = config.load()
  if (!cfg.realtime || !cfg.realtime.url || !cfg.realtime.anon_key) {
    log.warn("Sin datos de Realtime: el agente va a funcionar por consulta periódica")
    return false
  }

  try {
    const { createClient } = require("@supabase/supabase-js")

    cliente = createClient(cfg.realtime.url, cfg.realtime.anon_key, {
      auth: { persistSession: false, autoRefreshToken: false },
      realtime: { params: { eventsPerSecond: 20 } },
    })

    canal = cliente
      .channel(cfg.realtime.channel, { config: { private: false } })
      .on("broadcast", { event: "job" }, () => onRing())
      .subscribe((estado) => {
        if (estado === "SUBSCRIBED") log.info("Timbre conectado")
        else if (estado === "CHANNEL_ERROR" || estado === "TIMED_OUT") {
          log.warn(`Timbre desconectado (${estado}); se reintenta solo`)
        }
      })

    return true
  } catch (e) {
    log.warn("No se pudo conectar el timbre:", e.message)
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
}

module.exports = { connect, disconnect }
