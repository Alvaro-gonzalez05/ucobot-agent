"use strict"

const { exec } = require("child_process")
const config = require("./config")
const log = require("./logger")
const api = require("./api")
const doorbell = require("./doorbell")
const setupServer = require("./setup-server")
const { handlers } = require("./jobs")
const { listPrinters, printerHealth } = require("./printers/list")
const { printRawWindows } = require("./printers/windows-raw")
const { printRawTcp, esDestinoDeRed } = require("./printers/tcp")
const { VERSION } = require("./capabilities")

/**
 * UcoBot Agent.
 *
 * Corre en la PC del local y ejecuta lo que UcoBot le pide: imprimir un ticket
 * sin diálogo, abrir la gaveta, listar impresoras. Trabaja por cola, así que un
 * pedido que entra por WhatsApp a la madrugada se imprime igual aunque no haya
 * nadie con el navegador abierto.
 *
 * PRINCIPIO QUE NO SE NEGOCIA: esto no puede morirse.
 * Es un programa que va a estar meses sin que nadie lo mire, en una PC de un
 * local, con una impresora que a veces se queda sin papel y una conexión que a
 * veces se cae. Ningún error individual termina el proceso: se loguea, se reporta
 * y se sigue. Lo peor que puede pasar es que un ticket salga por el navegador,
 * que es exactamente como salía antes de que este programa existiera.
 */

const HEARTBEAT_MS = 30_000
/** Consulta de reserva por si el timbre se cayó sin avisar. */
const POLL_RESERVA_MS = 60_000
/** El inventario de impresoras cambia poco; no hace falta mirarlo seguido. */
const REFRESCO_IMPRESORAS_MS = 5 * 60_000

const estado = {
  timbreConectado: false,
  ultimoTrabajo: null,
  trabajosHechos: 0,
  trabajosFallidos: 0,
  impresoras: [],
  corriendo: false,
  onPaired: () => arrancarCiclo(),
  onUnpaired: () => detenerCiclo(),
  imprimirPrueba: () => imprimirPrueba(),
}

let timerLatido = null
let timerPoll = null
let timerImpresoras = null
let procesando = false

// --- Ciclo de trabajo -------------------------------------------------------

async function refrescarImpresoras() {
  try {
    estado.impresoras = await listPrinters()
  } catch (e) {
    log.warn("No se pudieron listar las impresoras:", e.message)
  }
}

async function latir() {
  try {
    const r = await api.heartbeat({ printers: estado.impresoras, health: printerHealth() })
    // Si el servidor dice que hay trabajo esperando, el timbre no llegó: lo
    // agarramos ahora en vez de esperar al siguiente ciclo.
    if (r && r.pending > 0) procesarTrabajos()
  } catch (e) {
    if (e.status === 401) {
      log.warn("El dashboard revocó este equipo. Hay que volver a vincularlo.")
      config.unpair()
      detenerCiclo()
      return
    }
    log.warn("Falló el latido:", e.message)
  }
}

async function procesarTrabajos() {
  // Un solo procesamiento a la vez: el timbre puede sonar varias veces seguidas
  // y no queremos dos tandas peleándose por la misma impresora.
  if (procesando || !config.isPaired()) return
  procesando = true

  try {
    let trabajos = await api.claimJobs(5)

    while (trabajos.length > 0) {
      for (const trabajo of trabajos) {
        await ejecutar(trabajo)
      }
      // Puede haber más esperando (varios tickets juntos).
      trabajos = await api.claimJobs(5)
    }
  } catch (e) {
    if (e.status === 401) {
      config.unpair()
      detenerCiclo()
    } else {
      log.warn("No se pudo traer trabajo:", e.message)
    }
  } finally {
    procesando = false
  }
}

async function ejecutar(trabajo) {
  const handler = handlers[trabajo.type]
  estado.ultimoTrabajo = new Date().toISOString()

  if (!handler) {
    // No debería pasar: el servidor filtra por capabilities. Si pasa igual, se
    // reporta con un mensaje claro en vez de dejar el trabajo colgado.
    log.warn("Tipo de trabajo desconocido:", trabajo.type)
    estado.trabajosFallidos++
    await reportarSeguro(trabajo.id, false, {
      error: `Este equipo no sabe hacer "${trabajo.type}". Actualizá el agente.`,
    })
    return
  }

  try {
    const resultado = await handler(trabajo.payload || {})
    estado.trabajosHechos++
    await reportarSeguro(trabajo.id, true, resultado || {})
  } catch (e) {
    estado.trabajosFallidos++
    log.error(`Falló ${trabajo.type}:`, e.message)
    // El mensaje va tal cual al dashboard: tiene que servirle a alguien que está
    // parado frente a la impresora, no a un programador.
    await reportarSeguro(trabajo.id, false, { error: e.message })
  }
}

async function reportarSeguro(jobId, ok, extra) {
  try {
    await api.reportResult(jobId, ok, extra)
  } catch (e) {
    // Si no podemos avisar, el trabajo queda 'running' y vence solo. Es preferible
    // a reintentar en loop contra un servidor caído.
    log.warn("No se pudo reportar el resultado:", e.message)
  }
}

// --- Prueba desde la pantalla local ----------------------------------------

/**
 * Ticket de prueba armado acá adentro.
 *
 * Es la única vez que el agente arma contenido: sirve para diagnosticar sin
 * depender de que UcoBot esté al alcance, por ejemplo cuando se está instalando
 * y todavía no se vinculó.
 */
async function imprimirPrueba() {
  const cfg = config.load()
  let impresora = (cfg.settings || {}).defaultPrinter
  if (!impresora) {
    if (estado.impresoras.length === 0) await refrescarImpresoras()
    const predeterminada = estado.impresoras.find((p) => p.isDefault) || estado.impresoras[0]
    if (!predeterminada) throw new Error("No se detectó ninguna impresora en este equipo")
    impresora = predeterminada.name
  }

  const linea = (s) => Buffer.from(`${s}\n`, "latin1")
  const bytes = Buffer.concat([
    Buffer.from([0x1b, 0x40]), // inicializar
    Buffer.from([0x1b, 0x61, 0x01]), // centrado
    Buffer.from([0x1d, 0x21, 0x11]), // doble alto y ancho
    linea("UCOBOT"),
    Buffer.from([0x1d, 0x21, 0x00]),
    linea("Prueba desde el agente"),
    linea(new Date().toLocaleString("es-AR")),
    linea(""),
    linea(impresora.slice(0, 30)),
    linea(`v${VERSION}`),
    linea(""),
    linea("Si lees esto, funciona."),
    Buffer.from([0x1b, 0x64, 0x04]), // avanzar papel
    Buffer.from([0x1d, 0x56, 0x42, 0x00]), // corte parcial
  ])

  return esDestinoDeRed(impresora)
    ? printRawTcp(impresora, bytes)
    : printRawWindows(impresora, bytes)
}

// --- Arranque y parada ------------------------------------------------------

async function arrancarCiclo() {
  if (estado.corriendo) return
  estado.corriendo = true

  await refrescarImpresoras()
  await latir()

  estado.timbreConectado = await doorbell.connect(() => procesarTrabajos())

  timerLatido = setInterval(latir, HEARTBEAT_MS)
  timerPoll = setInterval(procesarTrabajos, POLL_RESERVA_MS)
  timerImpresoras = setInterval(refrescarImpresoras, REFRESCO_IMPRESORAS_MS)

  // Por si quedó algo de antes de que se apagara la PC.
  procesarTrabajos()
  log.info("Agente en marcha")
}

function detenerCiclo() {
  estado.corriendo = false
  estado.timbreConectado = false
  clearInterval(timerLatido)
  clearInterval(timerPoll)
  clearInterval(timerImpresoras)
  doorbell.disconnect()
}

function abrirPantalla() {
  // --no-open: para correrlo como servicio o en pruebas, donde abrir un navegador
  // no tiene sentido (o directamente no hay escritorio).
  if (process.argv.includes("--no-open")) return
  const url = `http://localhost:${setupServer.PUERTO}`
  if (process.platform === "win32") exec(`start "" "${url}"`, { windowsHide: true })
  else if (process.platform === "darwin") exec(`open "${url}"`)
  else exec(`xdg-open "${url}"`)
}

async function main() {
  log.info(`UcoBot Agent v${VERSION} arrancando`)
  setupServer.start(estado)

  if (config.isPaired()) {
    await arrancarCiclo()
    // Con --show se puede abrir la pantalla a mano desde un acceso directo.
    if (process.argv.includes("--show")) abrirPantalla()
  } else {
    log.info("Sin vincular: abriendo la pantalla de configuración")
    await refrescarImpresoras()
    abrirPantalla()
  }
}

// Nada tumba al agente. Si algo se escapa, queda en el log y el ciclo sigue.
process.on("uncaughtException", (e) => log.error("Excepción no atrapada:", e.stack || e.message))
process.on("unhandledRejection", (e) => log.error("Promesa rechazada:", (e && e.message) || e))

main()
