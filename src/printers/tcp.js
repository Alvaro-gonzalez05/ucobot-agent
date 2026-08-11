"use strict"

const net = require("net")

/**
 * Impresoras de red: los bytes van directo al puerto 9100 (RAW / JetDirect), que
 * es lo que hablan todas las térmicas con Ethernet o WiFi.
 *
 * Es el camino más corto que existe — ni spooler, ni driver, ni Windows de por
 * medio — y además permite que la impresora de la cocina esté en otra máquina
 * que la caja. La contra es que si la impresora está apagada uno se entera por
 * timeout y no por un error claro; por eso el timeout es corto.
 */

/** ¿El destino es una impresora de red? ("192.168.0.50" o "192.168.0.50:9100") */
function esDestinoDeRed(destino) {
  return /^(\d{1,3}\.){3}\d{1,3}(:\d+)?$/.test(String(destino || "").trim())
}

function printRawTcp(destino, bytes, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const [host, puerto] = String(destino).split(":")
    const socket = new net.Socket()
    let resuelto = false

    const fallar = (msg) => {
      if (resuelto) return
      resuelto = true
      socket.destroy()
      reject(new Error(msg))
    }

    socket.setTimeout(timeoutMs)
    socket.on("timeout", () => fallar(`La impresora ${host} no responde`))
    socket.on("error", (e) => fallar(`No se pudo conectar a ${host}: ${e.message}`))

    socket.connect(Number(puerto) || 9100, host, () => {
      socket.write(bytes, () => {
        // Un respiro antes de cortar: si uno cierra el socket en el mismo
        // instante, algunas impresoras descartan lo último que llegó.
        setTimeout(() => {
          if (resuelto) return
          resuelto = true
          socket.end()
          resolve({ bytes: bytes.length, printer: destino })
        }, 250)
      })
    })
  })
}

module.exports = { printRawTcp, esDestinoDeRed }
