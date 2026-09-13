"use strict"

/**
 * El puente de la app de escritorio, del lado de la página.
 *
 * Publica en la web el mismo objeto que la app de Android: `window.UcoBotNative`,
 * con `postMessage` y `addEventListener("message")`. Así toda la web que ya sabe
 * hablar con la app (lib/native-app.ts: imprimir, vincularse, la gaveta) funciona
 * igual en Windows, sin una sola línea distinta.
 *
 * SÓLO EN UCOBOT. El objeto se publica únicamente si la página es del dominio de
 * UcoBot. Un link de afuera que llegue a abrirse en la app no lo ve, y no puede
 * pedir imprimir ni vincular el equipo. El proceso principal vuelve a revisar el
 * origen de cada pedido, por las dudas.
 *
 * Corre en modo sandbox: sin Node, sólo con lo que Electron deja pasar.
 */

const { contextBridge, ipcRenderer } = require("electron")

function argumento(nombre) {
  const prefijo = `--${nombre}=`
  const encontrado = process.argv.find((a) => a.startsWith(prefijo))
  return encontrado ? encontrado.slice(prefijo.length) : null
}

const ORIGEN = argumento("ucobot-origen")

if (ORIGEN && window.location.origin === ORIGEN) {
  const oyentes = new Set()

  contextBridge.exposeInMainWorld("UcoBotNative", {
    postMessage(texto) {
      ipcRenderer
        .invoke("ucobot:puente", String(texto))
        .then((respuesta) => {
          for (const oyente of oyentes) {
            try {
              oyente({ data: respuesta })
            } catch {
              // Un oyente roto de la página no puede cortar a los demás.
            }
          }
        })
        .catch(() => {})
    },
    addEventListener(tipo, oyente) {
      if (tipo === "message" && typeof oyente === "function") oyentes.add(oyente)
    },
  })

  // En escritorio la alarma de pedidos es la de la web, aunque la ventana esté
  // minimizada: la app le pide a Chromium que no frene la página en segundo plano.
  // (En Android es al revés: con la app al fondo suena la alarma nativa.)
  contextBridge.exposeInMainWorld("__ucobotAlarmaEnLaWeb", true)
}
