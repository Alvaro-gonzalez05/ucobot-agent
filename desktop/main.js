"use strict"

/**
 * UcoBot para Windows.
 *
 * La misma idea que la app de Android: UcoBot entero en una sola instalación.
 * La ventana carga la web de producción —cada deploy llega al instante— y el
 * agente de impresión corre ADENTRO de este mismo proceso: es el mismo código de
 * src/ que usa el .exe suelto, no una copia.
 *
 * Qué resuelve cada parte:
 *  - La ventana: UcoBot, sin barra de navegador. Cerrarla la manda a la bandeja;
 *    así sigue imprimiendo, sonando y avisando. "Salir" desde la bandeja la cierra.
 *  - El agente: los tickets que llegan de afuera (WhatsApp, otra caja) y los que
 *    se mandan desde esta misma PC, directo a la impresora.
 *  - El puente (preload.js): la web le pide a la app imprimir, abrir la gaveta,
 *    vincularse. Mismo protocolo que Android.
 *  - Notificaciones de Windows para los avisos de UcoBot y los pedidos nuevos.
 *  - Arranque con Windows, en la bandeja.
 */

const { app, BrowserWindow, Tray, Menu, Notification, ipcMain, shell, nativeImage } = require("electron")
const path = require("path")
const fs = require("fs")
const os = require("os")
const https = require("https")
const { spawn } = require("child_process")

const SERVIDOR = (process.env.UCOBOT_SERVER_URL || "https://chatbot-sass-eight.vercel.app").replace(/\/+$/, "")
const ORIGEN = new URL(SERVIDOR).origin

// Antes de cargar el agente: con esto el servidor sabe que es la app y no el
// .exe suelto, y le manda su instalador cuando hay una versión nueva.
process.env.UCOBOT_PLATFORM = "windows-app"
process.env.UCOBOT_SERVER_URL = SERVIDOR

const config = require("../src/config")
const log = require("../src/logger")
const api = require("../src/api")
const agente = require("../src/index")
const setupServer = require("../src/setup-server")
const installer = require("../src/installer")
const { registrarCapacidad } = require("../src/jobs/index")
const { listPrinters } = require("../src/printers/list")
const { printRawWindows } = require("../src/printers/windows-raw")
const { printRawTcp, esDestinoDeRed } = require("../src/printers/tcp")

/**
 * Logins que se niegan a funcionar dentro de una app: van al navegador del
 * sistema. Los flujos que dependen de ellos ya saben volver solos (ver
 * lib/meta/oauth-state.ts y app/abrir-app).
 */
const DEL_NAVEGADOR = ["facebook.com", "fb.com", "meta.com", "instagram.com", "whatsapp.com", "ycloud.com"]

const PULSO_GAVETA = Buffer.from([0x1b, 0x70, 0x00, 0x19, 0xfa])

let ventana = null
let bandeja = null
let ventanaEstado = null
let saliendo = false
let avisoDeBandejaMostrado = false

// --- Una sola instancia -------------------------------------------------------
//
// Abrir UcoBot dos veces daría dos agentes peleando por la misma impresora. La
// segunda instancia le pasa a la primera lo que traía (por ejemplo, el enlace
// ucobot:// con el que Chrome devuelve a la app) y se cierra.
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on("second-instance", (_evento, argumentos) => {
    mostrarVentana()
    const enlace = argumentos.find((a) => a.startsWith("ucobot://"))
    if (enlace) abrirEnlace(enlace)
  })
  app.whenReady().then(iniciar)
}

async function iniciar() {
  app.setAppUserModelId("com.codea.ucobot.desktop")

  if (app.isPackaged) {
    // ucobot://abrir?ruta=... — Chrome devuelve a la app después de conectar
    // Instagram o Messenger, que no se pueden conectar adentro.
    app.setAsDefaultProtocolClient("ucobot")
    // Arranca con Windows, en la bandeja: los pedidos de WhatsApp se imprimen
    // aunque nadie abra la app después de un corte de luz.
    app.setLoginItemSettings({ openAtLogin: true, args: ["--oculta"] })
    // El agente suelto de antes deja de arrancar solo: la app lo reemplaza. La
    // vinculación se conserva (vive en ProgramData), así que no hay que volver a
    // vincular la PC. Una sola vez: desinstalar lanza PowerShell y demora el
    // arranque, y después del primero ya no queda nada que retirar.
    if (!config.load().agenteSueltoRetirado) {
      try {
        installer.desinstalar()
        config.save({ agenteSueltoRetirado: true })
      } catch (e) {
        log.warn("No se pudo retirar el agente suelto:", e.message)
      }
    }
  }

  configurarAgente()
  crearBandeja()
  crearVentana(process.argv.includes("--oculta"))

  const enlace = process.argv.find((a) => a.startsWith("ucobot://"))
  if (enlace) abrirEnlace(enlace)
}

app.on("window-all-closed", () => {
  // La app vive en la bandeja: cerrar la ventana no la termina.
})

app.on("before-quit", () => {
  saliendo = true
  try {
    agente.detenerCiclo()
  } catch {
    // Ya estaba detenido.
  }
})

// --- Ventana ------------------------------------------------------------------

function crearVentana(oculta) {
  ventana = new BrowserWindow({
    width: 1320,
    height: 860,
    minWidth: 960,
    minHeight: 620,
    show: false,
    title: "UcoBot",
    backgroundColor: "#1C1C28",
    icon: rutaIcono(),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      additionalArguments: [`--ucobot-origen=${ORIGEN}`],
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      // Con la ventana minimizada o en la bandeja, Chromium frena la página: la
      // alarma de pedidos se cortaba y el aviso en tiempo real se atrasaba.
      backgroundThrottling: false,
      spellcheck: false,
    },
  })
  ventana.removeMenu()

  // La web sabe que corre adentro de la app por el agente de usuario, igual que
  // en Android. Se saca "Electron": no aporta y hay servicios que lo rechazan.
  const agenteDeUsuario = ventana.webContents
    .getUserAgent()
    .replace(/\s*Electron\/\S+/i, "")
    .replace(/\s*ucobot-agent\/\S+/i, "")
  ventana.webContents.setUserAgent(`${agenteDeUsuario} UcoBotApp/${app.getVersion()}`)

  ventana.once("ready-to-show", () => {
    if (!oculta) ventana.show()
  })

  ventana.on("close", (evento) => {
    if (saliendo) return
    evento.preventDefault()
    ventana.hide()
    if (!avisoDeBandejaMostrado && Notification.isSupported()) {
      avisoDeBandejaMostrado = true
      new Notification({
        title: "UcoBot sigue funcionando",
        body: "Quedó en la bandeja: sigue imprimiendo y avisando los pedidos. Para cerrarlo del todo, clic derecho en el ícono → Salir.",
        icon: rutaIcono(),
      }).show()
    }
  })

  // Ventanas nuevas (window.open). Conectar Mercado Pago abre una que empieza y
  // termina en UcoBot y necesita la sesión: se abre adentro de la app, que la
  // comparte, y como es una ventana de verdad window.opener funciona. Los logins
  // de Meta van al navegador.
  ventana.webContents.setWindowOpenHandler(({ url }) => {
    if (vaAlNavegador(url)) {
      shell.openExternal(url)
      return { action: "deny" }
    }
    return {
      action: "allow",
      overrideBrowserWindowOptions: {
        width: 560,
        height: 780,
        parent: ventana,
        autoHideMenuBar: true,
        icon: rutaIcono(),
        backgroundColor: "#FFFFFF",
        webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false },
      },
    }
  })

  ventana.webContents.on("did-create-window", (hija) => {
    hija.removeMenu()
    hija.webContents.setWindowOpenHandler(({ url }) => {
      shell.openExternal(url)
      return { action: "deny" }
    })
    hija.webContents.on("will-navigate", (evento, url) => {
      if (vaAlNavegador(url)) {
        evento.preventDefault()
        shell.openExternal(url)
      }
    })
  })

  // Navegar a otro sitio desde la ventana principal (el login de Facebook para
  // conectar Instagram) se abre en el navegador; la app se queda en UcoBot.
  ventana.webContents.on("will-navigate", (evento, url) => {
    if (esDeUcoBot(url) || url.startsWith("file:")) return
    evento.preventDefault()
    shell.openExternal(url)
  })

  ventana.webContents.on("did-fail-load", (_e, codigo, descripcion, url, esPrincipal) => {
    // -3 es "cancelada": pasa al navegar rápido y no es un error.
    if (!esPrincipal || codigo === -3) return
    log.warn(`No cargó ${url}: ${descripcion} (${codigo})`)
    ventana.loadFile(path.join(__dirname, "sin-conexion.html"), { query: { destino: url || `${SERVIDOR}/dashboard` } })
  })

  ventana.loadURL(`${SERVIDOR}/dashboard`)
}

function mostrarVentana() {
  if (!ventana || ventana.isDestroyed()) {
    crearVentana(false)
    return
  }
  if (ventana.isMinimized()) ventana.restore()
  ventana.show()
  ventana.focus()
}

function esDeUcoBot(url) {
  try {
    return new URL(url).origin === ORIGEN
  } catch {
    return false
  }
}

function vaAlNavegador(url) {
  try {
    const host = new URL(url).hostname.toLowerCase()
    return DEL_NAVEGADOR.some((d) => host === d || host.endsWith(`.${d}`))
  } catch {
    return false
  }
}

/** ucobot://abrir?ruta=/dashboard/bots?ig_connected=1 */
function abrirEnlace(enlace) {
  try {
    const u = new URL(enlace)
    if (u.hostname !== "abrir") return
    const ruta = u.searchParams.get("ruta") || "/dashboard"
    // Sólo rutas propias: un enlace de afuera no puede hacer cargar otro sitio.
    if (!ruta.startsWith("/") || ruta.startsWith("//")) return
    mostrarVentana()
    ventana.loadURL(`${SERVIDOR}${ruta}`)
  } catch (e) {
    log.warn("Enlace ucobot:// inválido:", e.message)
  }
}

function rutaIcono() {
  return path.join(__dirname, "build", "icon.png")
}

// --- Bandeja ------------------------------------------------------------------

function crearBandeja() {
  const imagen = nativeImage.createFromPath(path.join(__dirname, "build", "tray.png"))
  bandeja = new Tray(imagen.isEmpty() ? nativeImage.createFromPath(rutaIcono()) : imagen)
  bandeja.setToolTip("UcoBot")
  bandeja.on("click", mostrarVentana)
  bandeja.setContextMenu(
    Menu.buildFromTemplate([
      { label: "Abrir UcoBot", click: mostrarVentana },
      { label: "Estado de la impresora", click: abrirEstado },
      { type: "separator" },
      {
        label: "Salir de UcoBot",
        click: () => {
          saliendo = true
          app.quit()
        },
      },
    ])
  )
}

/** La pantalla local del agente de siempre: impresora, timbre, prueba. */
function abrirEstado() {
  if (ventanaEstado && !ventanaEstado.isDestroyed()) {
    ventanaEstado.show()
    ventanaEstado.focus()
    return
  }
  ventanaEstado = new BrowserWindow({
    width: 560,
    height: 720,
    title: "Estado de la impresora",
    icon: rutaIcono(),
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false },
  })
  ventanaEstado.removeMenu()
  ventanaEstado.loadURL(`http://127.0.0.1:${setupServer.PUERTO}`)
}

// --- Agente -------------------------------------------------------------------

function configurarAgente() {
  // Notificaciones de UcoBot ("Solicitud de Humano", "Pago recibido"...). En la
  // web llegan por web push, que adentro de la app no existe.
  registrarCapacidad("notify.show", async (payload) => {
    notificar(payload && payload.title, payload && payload.message, payload && payload.link)
    return { mostrada: true }
  })

  // Pedido nuevo del bot. La alarma que repite la sigue dando la web (la página
  // no se frena con la ventana escondida); acá va el aviso de Windows para quien
  // no está mirando la pantalla.
  registrarCapacidad("order.alert", async (payload) => {
    notificar("Pedido nuevo", (payload && payload.label) || "Esperando confirmación en UcoBot", "/dashboard/pedidos")
    return { mostrada: true }
  })

  // Una versión nueva de la app: se baja el instalador y se instala solo.
  agente.estado.aplicarActualizacion = actualizarApp

  try {
    setupServer.start(agente.estado)
  } catch (e) {
    log.warn("No se pudo abrir la pantalla local del agente:", e.message)
  }

  if (config.isPaired()) {
    agente.arrancarCiclo()
  } else {
    agente.refrescarImpresoras()
  }

  ipcMain.handle("ucobot:puente", atenderPuente)
}

function notificar(titulo, mensaje, link) {
  // Con UcoBot a la vista ya lo muestra la campanita, con su sonido.
  if (ventana && !ventana.isDestroyed() && ventana.isVisible() && ventana.isFocused()) return
  if (!Notification.isSupported()) return
  const aviso = new Notification({
    title: titulo || "UcoBot",
    body: mensaje || "",
    icon: rutaIcono(),
  })
  aviso.on("click", () => {
    mostrarVentana()
    if (typeof link === "string" && link.startsWith("/") && !link.startsWith("//")) {
      ventana.loadURL(`${SERVIDOR}${link}`)
    }
  })
  aviso.show()
}

// --- Puente con la web ----------------------------------------------------------

async function atenderPuente(evento, texto) {
  let pedido
  try {
    pedido = JSON.parse(texto)
  } catch {
    return JSON.stringify({ id: null, ok: false, error: "Pedido ilegible" })
  }
  const id = pedido && pedido.id

  // Segunda revisión del origen: sólo una página de UcoBot puede usar el puente.
  const origenDelPedido = (() => {
    try {
      return new URL(evento.senderFrame.url).origin
    } catch {
      return null
    }
  })()
  if (origenDelPedido !== ORIGEN) {
    return JSON.stringify({ id, ok: false, error: "No autorizado" })
  }

  try {
    const resultado = await ejecutar(pedido.method, pedido.params || {})
    return JSON.stringify({ id, ok: true, result: resultado === undefined ? null : resultado })
  } catch (e) {
    return JSON.stringify({ id, ok: false, error: (e && e.message) || "La app no pudo hacerlo" })
  }
}

async function ejecutar(metodo, params) {
  const cfg = config.load()
  const ajustes = cfg.settings || {}

  switch (metodo) {
    case "info":
      return {
        version: app.getVersion(),
        paired: config.isPaired(),
        agentId: cfg.agentId || null,
        deviceId: api.dispositivo(),
        agentName: cfg.name || null,
        ticketWidth: ajustes.ticketWidth === 58 ? 58 : 80,
        printerReady: !!(await impresoraDestino()),
        doorbell: !!agente.estado.timbreConectado,
      }

    case "pair": {
      const codigo = String(params.code || "").trim().toUpperCase()
      if (!codigo) throw new Error("Falta el código de vinculación")
      const impresoras = await listPrinters().catch(() => [])
      const datos = await api.pair(codigo, { printers: impresoras })
      await agente.arrancarCiclo()
      return { agentId: datos.agent_id, name: datos.name }
    }

    case "unpair":
      agente.detenerCiclo()
      config.unpair()
      return null

    case "print": {
      const datos = String(params.data_b64 || "")
      if (!datos) throw new Error("El ticket vino vacío")
      await imprimir(Buffer.from(datos, "base64"))
      return null
    }

    case "openDrawer":
      await imprimir(PULSO_GAVETA)
      return null

    case "openStatus":
      abrirEstado()
      return null

    default:
      throw new Error(`La app no conoce "${metodo}". Actualizala.`)
  }
}

/**
 * La impresora de esta PC: la que se eligió en el panel de UcoBot Agent o, si no
 * se eligió ninguna, la predeterminada de Windows.
 */
async function impresoraDestino() {
  const elegida = (config.load().settings || {}).defaultPrinter
  if (elegida) return elegida
  const impresoras = await listPrinters().catch(() => [])
  const predeterminada = impresoras.find((p) => p.isDefault) || impresoras[0]
  return predeterminada ? predeterminada.name : null
}

async function imprimir(bytes) {
  const impresora = await impresoraDestino()
  if (!impresora) throw new Error("No hay ninguna impresora configurada en esta PC")
  return esDestinoDeRed(impresora) ? printRawTcp(impresora, bytes) : printRawWindows(impresora, bytes)
}

// --- Actualización ----------------------------------------------------------------

let actualizando = false

/**
 * Baja el instalador de la versión nueva y lo corre en silencio.
 *
 * Nunca en el medio de un ticket (el agente avisa si está procesando). El
 * instalador cierra esta app, se instala encima y la vuelve a abrir
 * (--force-run). Si algo falla, se reintenta en el próximo latido.
 */
async function actualizarApp(update, hayTrabajoEnCurso) {
  if (!app.isPackaged || actualizando) return
  if (!update || !update.url || !update.version) return
  if (typeof hayTrabajoEnCurso === "function" && hayTrabajoEnCurso()) return
  actualizando = true

  const destino = path.join(os.tmpdir(), `UcoBot-Setup-${update.version}.exe`)
  try {
    log.info(`Bajando UcoBot ${update.version}`)
    await descargar(update.url, destino)
    const tam = fs.statSync(destino).size
    if (tam < 20 * 1024 * 1024) throw new Error(`el instalador bajó incompleto (${Math.round(tam / 1024)} KB)`)
    log.info(`Instalando UcoBot ${update.version}`)
    spawn(destino, ["/S", "--force-run"], { detached: true, stdio: "ignore", windowsHide: true }).unref()
    saliendo = true
    setTimeout(() => app.quit(), 1500)
  } catch (e) {
    log.warn("No se pudo actualizar:", e.message)
    actualizando = false
  }
}

function descargar(url, destino, saltos = 0) {
  return new Promise((resolve, reject) => {
    if (saltos > 5) return reject(new Error("demasiados redirects"))
    https
      .get(url, { headers: { "User-Agent": `UcoBot/${app.getVersion()}` } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume()
          return resolve(descargar(res.headers.location, destino, saltos + 1))
        }
        if (res.statusCode !== 200) {
          res.resume()
          return reject(new Error(`HTTP ${res.statusCode}`))
        }
        const archivo = fs.createWriteStream(destino)
        res.pipe(archivo)
        archivo.on("finish", () => archivo.close(resolve))
        archivo.on("error", reject)
      })
      .on("error", reject)
  })
}

process.on("uncaughtException", (e) => log.error("Excepción no atrapada:", (e && e.stack) || e))
process.on("unhandledRejection", (e) => log.error("Promesa rechazada:", (e && e.message) || e))
