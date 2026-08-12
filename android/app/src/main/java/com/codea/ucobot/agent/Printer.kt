package com.codea.ucobot.agent

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/**
 * La impresora integrada del POSNET.
 *
 * CÓMO SE LLEGA A ELLA
 * El SDK de iMin publica la impresora como un dispositivo Bluetooth *virtual*
 * llamado "InnerPrinter". No hay radio de por medio: es la forma que eligió el
 * fabricante para exponerla al sistema. Un socket RFCOMM es un flujo de bytes,
 * así que se le escriben los mismos ESC/POS que arma la web para las térmicas de
 * Windows, sin traducir nada.
 *
 * POR QUÉ LA CONEXIÓN SE MANTIENE ABIERTA
 * La primera versión abría y cerraba el socket en cada ticket, con el argumento
 * de que así siempre se arrancaba de cero. En un iMin Swift 2 Pro eso se degrada
 * de una forma fea y medible: los primeros tickets salen en un segundo, después
 * empiezan a tardar 17 y 29 segundos, y al rato la impresora **desaparece de la
 * lista de dispositivos** y ya no imprime nada hasta reiniciar. El stack de
 * Bluetooth de Android no está hecho para que le abran y cierren sockets todo el
 * día.
 *
 * Ahora el socket se abre una vez y se reusa. Si una escritura falla, se cierra,
 * se espera un momento y se reconecta una sola vez — que cubre el caso real de
 * que alguien apague y prenda el equipo.
 *
 * También se cachea el `BluetoothDevice`: consultar `bondedDevices` en cada
 * ticket y en cada latido era otra forma de castigar al mismo stack.
 */
object Printer {

    private const val TAG = "UcoBotPrinter"

    /** Nombres con los que los fabricantes publican la impresora integrada. */
    private val NOMBRES = listOf("InnerPrinter", "BluetoothPrinter", "iMinPrinter")

    /** UUID del perfil de puerto serie: el estándar para hablar con impresoras. */
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    class PrinterException(message: String) : Exception(message)

    private var dispositivo: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var salida: OutputStream? = null

    /** Última vez que se buscó el dispositivo, para no rastrillar en cada latido. */
    private var ultimaBusqueda = 0L
    private const val CACHE_BUSQUEDA_MS = 60_000L

    private val candado = Any()

    // --- Conexión ----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun buscarDispositivo(forzar: Boolean = false): BluetoothDevice? {
        val cacheado = dispositivo
        if (cacheado != null && !forzar &&
            System.currentTimeMillis() - ultimaBusqueda < CACHE_BUSQUEDA_MS
        ) {
            return cacheado
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return cacheado
        if (!adapter.isEnabled) return cacheado

        return try {
            val encontrado = adapter.bondedDevices?.firstOrNull { d ->
                NOMBRES.any { d.name?.contains(it, ignoreCase = true) == true }
            }
            ultimaBusqueda = System.currentTimeMillis()
            // Si en esta pasada no aparece, se conserva el que ya teníamos: el
            // listado se pone caprichoso justo cuando el stack está cargado, y
            // olvidarlo ahí es lo que producía el "no se encontró la impresora".
            if (encontrado != null) dispositivo = encontrado
            dispositivo
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para ver los dispositivos: ${e.message}")
            cacheado
        }
    }

    @SuppressLint("MissingPermission")
    private fun conectar() {
        if (salida != null && socket?.isConnected == true) return

        cerrar()

        val d = buscarDispositivo()
            ?: throw PrinterException(
                "No se encontró la impresora integrada. Revisá que el Bluetooth esté " +
                    "encendido y que la app tenga permiso de dispositivos cercanos."
            )

        try {
            val s = d.createRfcommSocketToServiceRecord(SPP)
            s.connect()
            socket = s
            salida = s.outputStream
            Log.i(TAG, "Conectado a ${d.name}")
        } catch (e: SecurityException) {
            throw PrinterException("Falta el permiso de dispositivos cercanos")
        } catch (e: Exception) {
            cerrar()
            throw PrinterException(e.message ?: "No se pudo conectar a la impresora")
        }
    }

    private fun cerrar() {
        try {
            salida?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        salida = null
        socket = null
    }

    /**
     * ¿Se ve la impresora? Lo consulta el latido para reportar el estado.
     * Usa el caché: no vale la pena molestar al Bluetooth cada 30 segundos.
     */
    fun disponible(): Boolean = synchronized(candado) {
        salida != null || buscarDispositivo() != null
    }

    // --- Impresión ---------------------------------------------------------

    /**
     * Manda bytes crudos a la impresora.
     *
     * Un solo reintento y con una espera en el medio: si el primer intento falla
     * por una conexión vieja, el segundo con el socket nuevo la arregla. Si el
     * segundo también falla, es un problema de verdad (sin papel, apagada) y hay
     * que avisarlo, no seguir insistiendo.
     */
    fun print(bytes: ByteArray) = synchronized(candado) {
        try {
            escribir(bytes)
        } catch (primera: Exception) {
            Log.w(TAG, "Falló la escritura, reconectando: ${primera.message}")
            cerrar()
            Thread.sleep(700)
            // Segundo intento buscando el dispositivo de nuevo por si cambió.
            buscarDispositivo(forzar = true)
            escribir(bytes)
        }
    }

    private fun escribir(bytes: ByteArray) {
        conectar()
        val out = salida ?: throw PrinterException("La impresora no está conectada")
        try {
            out.write(bytes)
            out.flush()
            // Respiro para que el buffer se vacíe antes de dar el trabajo por hecho:
            // sin esto, un corte de papel puede llegar antes que las últimas líneas.
            Thread.sleep(250)
            Log.i(TAG, "Impresos ${bytes.size} bytes")
        } catch (e: Exception) {
            throw PrinterException(e.message ?: "No se pudo imprimir")
        }
    }

    /** Pulso a la gaveta de dinero por el conector de la impresora. */
    fun abrirGaveta() = print(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()))

    /** Corta la conexión: se llama al apagar el servicio. */
    fun desconectar() = synchronized(candado) { cerrar() }

    /**
     * Ticket de prueba armado acá adentro.
     *
     * Es lo único que el agente genera por su cuenta: sirve para saber si la
     * impresora responde sin depender de que UcoBot esté al alcance.
     */
    fun ticketDePrueba(anchoMm: Int): ByteArray {
        val cols = if (anchoMm == 58) 32 else 48
        val salida = ArrayList<Byte>()
        fun raw(vararg b: Int) = b.forEach { salida.add(it.toByte()) }
        fun texto(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { salida.add(it) }
        fun linea(s: String = "") { texto(s); salida.add(0x0A) }

        raw(0x1B, 0x40)             // inicializar
        raw(0x1B, 0x74, 0x00)       // tabla de caracteres CP437
        raw(0x1B, 0x61, 0x01)       // centrado
        raw(0x1D, 0x21, 0x11)       // doble alto y ancho
        linea("UCOBOT")
        raw(0x1D, 0x21, 0x00)
        linea("Prueba de impresion")
        linea(
            java.text.SimpleDateFormat("dd/MM/yy HH:mm:ss", java.util.Locale("es", "AR"))
                .format(java.util.Date())
        )
        raw(0x1B, 0x61, 0x00)       // izquierda
        linea("-".repeat(cols))
        linea("Equipo: ${android.os.Build.MODEL}")
        linea("Ancho: ${anchoMm}mm")
        linea("-".repeat(cols))
        raw(0x1B, 0x61, 0x01)
        linea("Si lees esto, funciona.")
        linea("Los tickets salen sin dialogo.")
        raw(0x1B, 0x64, 0x04)       // avanzar papel
        raw(0x1D, 0x56, 0x42, 0x00) // corte parcial

        return salida.toByteArray()
    }
}
