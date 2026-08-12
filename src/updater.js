"use strict"

const fs = require("fs")
const os = require("os")
const path = require("path")
const { spawn } = require("child_process")
const log = require("./logger")
const { VERSION } = require("./capabilities")

/**
 * Actualización automática, silenciosa.
 *
 * POR QUÉ HACE FALTA: sin esto, cada arreglo obliga a ir equipo por equipo. Con
 * un local es una molestia; con quince es imposible. Y el caso que lo justifica
 * ya pasó — la v1.1.1 arregló un bug que dejaba la impresora muerta a los veinte
 * tickets: sin actualización automática, cada cliente instalado se quedaba roto
 * hasta que alguien viajara hasta ahí.
 *
 * CÓMO FUNCIONA, Y POR QUÉ ES TAN CORTO: el trabajo pesado ya estaba hecho. El
 * instalador (installer.js) sabe bajar el proceso viejo, copiarse encima y
 * relanzarse. Así que actualizar es simplemente bajar el .exe nuevo a una carpeta
 * temporal y ejecutarlo: él se encarga de reemplazar al que está corriendo, que
 * somos nosotros.
 *
 * Se corre una sola vez por arranque: si la descarga falla, se reintenta en el
 * próximo encendido y no cada 30 segundos contra GitHub.
 */

let yaIntentado = false

/**
 * @param {{version: string, url: string}} update Lo que mandó el latido.
 * @param {() => boolean} hayTrabajoEnCurso Para no cortar una impresión a la mitad.
 */
async function aplicar(update, hayTrabajoEnCurso) {
  if (!update || !update.version || !update.url) return
  if (yaIntentado) return
  if (process.platform !== "win32") return
  // Corriendo desde el código fuente (npm start) no hay nada que reemplazar.
  if (!process.pkg) return

  // Nunca en el medio de un ticket: el reemplazo mata este proceso.
  if (typeof hayTrabajoEnCurso === "function" && hayTrabajoEnCurso()) return

  yaIntentado = true
  log.info(`Hay una versión nueva (${update.version}, tenemos ${VERSION}). Descargando...`)

  const destino = path.join(os.tmpdir(), `UcoBotAgent-${update.version}.exe`)

  try {
    await descargar(update.url, destino)

    // Un .exe de menos de 10 MB es una descarga cortada o una página de error de
    // GitHub disfrazada de archivo. Ejecutar eso sería peor que no actualizar.
    const tam = fs.statSync(destino).size
    if (tam < 10 * 1024 * 1024) {
      throw new Error(`el archivo bajó incompleto (${Math.round(tam / 1024)} KB)`)
    }

    log.info(`Descargada la versión ${update.version}. Instalando y reiniciando...`)

    // --silent: que se instale sin abrir el navegador. En un local esto pasa a
    // media tarde y nadie quiere ver saltar una pestaña en la caja.
    const hijo = spawn(destino, ["--silent"], {
      detached: true,
      stdio: "ignore",
      windowsHide: true,
    })
    hijo.unref()

    // El proceso nuevo nos va a matar en un segundo; no hace falta hacer nada más.
  } catch (e) {
    log.warn(`No se pudo actualizar: ${e.message}. Se reintenta en el próximo arranque.`)
  }
}

/** Descarga siguiendo los redirects, que GitHub usa siempre para los assets. */
function descargar(url, destino, saltos = 0) {
  return new Promise((resolve, reject) => {
    if (saltos > 5) return reject(new Error("demasiados redirects"))

    const https = require("https")
    const req = https.get(url, { timeout: 60000 }, (res) => {
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
      archivo.on("finish", () => archivo.close(() => resolve()))
      archivo.on("error", (e) => {
        try {
          fs.unlinkSync(destino)
        } catch {}
        reject(e)
      })
    })

    req.on("timeout", () => {
      req.destroy()
      reject(new Error("la descarga tardó demasiado"))
    })
    req.on("error", reject)
  })
}

module.exports = { aplicar }
