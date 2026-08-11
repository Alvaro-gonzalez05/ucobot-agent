"use strict"

const http = require("http")
const os = require("os")
const config = require("./config")
const log = require("./logger")
const api = require("./api")
const { listPrinters, printerHealth } = require("./printers/list")
const { VERSION } = require("./capabilities")

/**
 * Pantalla local del agente (http://localhost:17845).
 *
 * El agente corre sin ventana, así que ésta es su única cara visible: acá se
 * pega el código de vinculación, se ve si está conectado y se leen los últimos
 * errores cuando algo no imprime.
 *
 * Es localhost puro y el navegador entra por la barra de direcciones — no hay una
 * página de internet haciendo fetch contra este puerto, así que nada de CORS,
 * mixed content ni Private Network Access. Ese enredo es justamente el que
 * evitamos al no hacer que la web le hable directo al agente.
 */

const PUERTO = Number(process.env.UCOBOT_AGENT_PORT) || 17845

function json(res, code, datos) {
  const cuerpo = JSON.stringify(datos)
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8" })
  res.end(cuerpo)
}

function leerCuerpo(req) {
  return new Promise((resolve) => {
    let datos = ""
    req.on("data", (c) => {
      datos += c
      if (datos.length > 1e6) req.destroy()
    })
    req.on("end", () => {
      try {
        resolve(JSON.parse(datos || "{}"))
      } catch {
        resolve({})
      }
    })
  })
}

function start(estado) {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${PUERTO}`)

    try {
      if (url.pathname === "/" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" })
        return res.end(PAGINA)
      }

      if (url.pathname === "/api/status" && req.method === "GET") {
        const cfg = config.load()
        return json(res, 200, {
          paired: !!(cfg.agentId && cfg.token),
          name: cfg.name,
          version: VERSION,
          hostname: os.hostname(),
          server: cfg.serverUrl,
          settings: cfg.settings || {},
          connected: estado.timbreConectado,
          lastJobAt: estado.ultimoTrabajo,
          jobsDone: estado.trabajosHechos,
          jobsFailed: estado.trabajosFallidos,
          printers: estado.impresoras,
          health: printerHealth(),
          log: log.tail(),
        })
      }

      if (url.pathname === "/api/pair" && req.method === "POST") {
        const { code } = await leerCuerpo(req)
        if (!code) return json(res, 400, { error: "Falta el código" })
        try {
          const impresoras = await listPrinters()
          const datos = await api.pair(String(code).trim().toUpperCase(), { printers: impresoras })
          estado.onPaired()
          return json(res, 200, { ok: true, name: datos.name })
        } catch (e) {
          return json(res, 400, { error: e.message })
        }
      }

      if (url.pathname === "/api/unpair" && req.method === "POST") {
        config.unpair()
        estado.onUnpaired()
        return json(res, 200, { ok: true })
      }

      if (url.pathname === "/api/test" && req.method === "POST") {
        try {
          const resultado = await estado.imprimirPrueba()
          return json(res, 200, { ok: true, ...resultado })
        } catch (e) {
          return json(res, 400, { error: e.message })
        }
      }

      json(res, 404, { error: "No encontrado" })
    } catch (e) {
      log.error("Error en la pantalla local:", e.message)
      json(res, 500, { error: e.message })
    }
  })

  // Sólo loopback: nadie de la red del local puede abrir esta pantalla.
  server.listen(PUERTO, "127.0.0.1", () => {
    log.info(`Pantalla de configuración en http://localhost:${PUERTO}`)
  })

  server.on("error", (e) => {
    if (e.code === "EADDRINUSE") log.error(`El puerto ${PUERTO} está ocupado: ¿hay otro agente corriendo?`)
    else log.error("La pantalla local falló:", e.message)
  })

  return server
}

const PAGINA = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>UcoBot Agent</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    background: #16161f; color: #f4f4f5; padding: 24px;
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  .card { width: 100%; max-width: 460px; background: #1e1e2a; border: 1px solid #2e2e3d; border-radius: 20px; padding: 28px; }
  h1 { margin: 0 0 4px; font-size: 20px; }
  .sub { margin: 0 0 22px; color: #9b9baa; font-size: 13px; }
  .estado { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 700; margin-bottom: 18px; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: #6b7280; }
  .dot.on { background: #22c55e; box-shadow: 0 0 0 4px rgba(34,197,94,.15); }
  label { display: block; font-size: 12px; color: #9b9baa; margin-bottom: 6px; }
  input {
    width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #3a3a4d; background: #14141c;
    color: #fff; font-size: 22px; font-family: ui-monospace, Consolas, monospace;
    letter-spacing: .18em; text-align: center; text-transform: uppercase;
  }
  input:focus { outline: none; border-color: #d1f366; }
  button {
    width: 100%; margin-top: 12px; padding: 13px; border: 0; border-radius: 12px;
    background: #d1f366; color: #1c1c28; font-size: 15px; font-weight: 800; cursor: pointer;
  }
  button:disabled { opacity: .5; cursor: default; }
  button.ghost { background: transparent; color: #9b9baa; border: 1px solid #3a3a4d; font-weight: 600; }
  .fila { display: flex; justify-content: space-between; font-size: 13px; padding: 9px 0; border-bottom: 1px solid #2a2a38; }
  .fila span:first-child { color: #9b9baa; }
  .msg { margin-top: 12px; font-size: 13px; padding: 10px 12px; border-radius: 10px; }
  .msg.err { background: rgba(239,68,68,.12); color: #fca5a5; }
  .msg.ok { background: rgba(34,197,94,.12); color: #86efac; }
  pre { background: #14141c; border: 1px solid #2a2a38; border-radius: 10px; padding: 10px; font-size: 11px;
        max-height: 190px; overflow: auto; color: #9b9baa; margin-top: 14px; white-space: pre-wrap; }
  details summary { cursor: pointer; font-size: 12px; color: #9b9baa; margin-top: 16px; }
</style>
</head>
<body>
  <div class="card" id="app">Cargando…</div>

<script>
const app = document.getElementById('app')
let ultimoMensaje = null

async function cargar() {
  try {
    const r = await fetch('/api/status')
    pintar(await r.json())
  } catch {
    app.innerHTML = '<h1>Sin conexión con el agente</h1><p class="sub">Cerrá esta pestaña y volvé a abrir el programa.</p>'
  }
}

function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])) }

function pintar(s) {
  const msg = ultimoMensaje ? '<div class="msg ' + ultimoMensaje.tipo + '">' + esc(ultimoMensaje.texto) + '</div>' : ''

  if (!s.paired) {
    app.innerHTML = \`
      <h1>Vincular esta computadora</h1>
      <p class="sub">Entrá a UcoBot → menú de tu perfil → Impresión directa, y generá el código.</p>
      <label>Código de vinculación</label>
      <input id="code" maxlength="9" placeholder="XXXX-XXXX" autofocus />
      <button id="btn">Vincular</button>
      \${msg}
      <details><summary>Registro técnico</summary><pre>\${esc(s.log.join('\\n'))}</pre></details>\`

    const input = document.getElementById('code')
    const btn = document.getElementById('btn')
    input.addEventListener('keydown', e => { if (e.key === 'Enter') btn.click() })
    btn.onclick = async () => {
      btn.disabled = true
      btn.textContent = 'Vinculando…'
      try {
        const r = await fetch('/api/pair', {
          method: 'POST', headers: {'Content-Type':'application/json'},
          body: JSON.stringify({ code: input.value })
        })
        const j = await r.json()
        ultimoMensaje = r.ok
          ? { tipo: 'ok', texto: 'Listo. Esta PC quedó vinculada.' }
          : { tipo: 'err', texto: j.error || 'No se pudo vincular' }
      } catch (e) {
        ultimoMensaje = { tipo: 'err', texto: 'No se pudo conectar con UcoBot' }
      }
      cargar()
    }
    return
  }

  // El servicio de cola de impresión detenido es LA causa silenciosa de "no
  // imprime": WMI falla, la impresora no responde y no hay ningún mensaje que lo
  // explique. Se avisa arriba de todo.
  const alertaSpooler = (s.health && s.health.spooler && s.health.spooler !== 'Running' && s.health.spooler !== 'unknown')
    ? '<div class="msg err">La cola de impresión de Windows está detenida (' + esc(s.health.spooler) + '). Ninguna impresora va a responder hasta que arranque: abrí Servicios y poné "Cola de impresión" en Automático.</div>'
    : ''

  app.innerHTML = \`
    <h1>\${esc(s.name || 'Esta computadora')}</h1>
    <p class="sub">UcoBot Agent v\${esc(s.version)}</p>
    <div class="estado"><span class="dot \${s.connected ? 'on' : ''}"></span>\${s.connected ? 'Conectado a UcoBot' : 'Reconectando…'}</div>
    \${alertaSpooler}
    <div class="fila"><span>Impresora</span><span>\${esc((s.settings && s.settings.defaultPrinter) || 'Sin elegir')}</span></div>
    <div class="fila"><span>Impresoras detectadas</span><span>\${(s.printers || []).length}</span></div>
    <div class="fila"><span>Tickets impresos</span><span>\${s.jobsDone || 0}</span></div>
    <div class="fila"><span>Con error</span><span>\${s.jobsFailed || 0}</span></div>
    <div class="fila"><span>Último trabajo</span><span>\${s.lastJobAt ? new Date(s.lastJobAt).toLocaleString('es-AR') : '—'}</span></div>
    <button id="test">Imprimir prueba</button>
    <button class="ghost" id="unpair">Desvincular esta PC</button>
    \${msg}
    <details><summary>Registro técnico</summary><pre>\${esc(s.log.join('\\n'))}</pre></details>\`

  document.getElementById('test').onclick = async (e) => {
    e.target.disabled = true
    e.target.textContent = 'Imprimiendo…'
    const r = await fetch('/api/test', { method: 'POST' })
    const j = await r.json()
    ultimoMensaje = r.ok
      ? { tipo: 'ok', texto: 'Salió el ticket de prueba.' }
      : { tipo: 'err', texto: j.error || 'No se pudo imprimir' }
    cargar()
  }

  document.getElementById('unpair').onclick = async () => {
    if (!confirm('¿Desvincular esta PC de UcoBot?')) return
    await fetch('/api/unpair', { method: 'POST' })
    ultimoMensaje = null
    cargar()
  }
}

cargar()
setInterval(() => { if (!document.querySelector('input:focus')) cargar() }, 5000)
</script>
</body>
</html>`

module.exports = { start, PUERTO }
