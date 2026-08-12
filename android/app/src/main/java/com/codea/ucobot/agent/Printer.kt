package com.codea.ucobot.agent

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.util.UUID

/**
 * La impresora integrada del POSNET.
 *
 * POR QUÉ POR BLUETOOTH Y NO POR EL SDK DE iMin
 * El SDK 2.0 de iMin ofrece tres caminos para llegar a la impresora, y uno de
 * ellos es un dispositivo Bluetooth *virtual* llamado "InnerPrinter". No hay
 * radio de por medio ni nada que emparejar por aire: es simplemente cómo el
 * fabricante publica la impresora al sistema operativo.
 *
 * Se eligió ese camino para la primera versión por un motivo práctico: usa API
 * estándar de Android y nada más. El camino por AIDL obliga a sumar la librería
 * de iMin y a acertarle a la firma exacta de sus métodos, algo que no se puede
 * verificar sin el equipo en la mano — y código que no se puede probar no debería
 * ser lo único que separa a un ticket de salir.
 *
 * Un socket RFCOMM es un flujo de bytes: se le escriben los mismos ESC/POS que
 * arma la web para las térmicas de Windows, sin traducir nada.
 *
 * SI ESTO NO FUNCIONA EN EL EQUIPO: la alternativa es `sendRAWData(fd, bytes,
 * callback)` del AIDL de iMin, que recibe exactamente el mismo array de bytes.
 * Cambia cómo se abre el canal, no lo que se manda.
 */
object Printer {

    private const val TAG = "UcoBotPrinter"

    /** Nombre con el que iMin publica la impresora integrada. */
    private val NOMBRES = listOf("InnerPrinter", "BluetoothPrinter", "iMinPrinter")

    /** UUID del perfil de puerto serie: el estándar para hablar con impresoras. */
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    class PrinterException(message: String) : Exception(message)

    @SuppressLint("MissingPermission")
    private fun buscarImpresora(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!adapter.isEnabled) return null
        return try {
            adapter.bondedDevices?.firstOrNull { d ->
                NOMBRES.any { d.name?.contains(it, ignoreCase = true) == true }
            }
        } catch (e: SecurityException) {
            // Falta el permiso BLUETOOTH_CONNECT: lo pide la pantalla principal.
            Log.w(TAG, "Sin permiso para ver los dispositivos: ${e.message}")
            null
        }
    }

    /** ¿Se ve la impresora integrada? Lo usa el latido para reportar el estado. */
    fun disponible(): Boolean = buscarImpresora() != null

    /**
     * Manda bytes crudos a la impresora.
     *
     * Se abre el socket, se escribe y se cierra en cada ticket en vez de mantener
     * la conexión abierta: una conexión viva durante horas se cae sola y hay que
     * detectarlo y reconectar, mientras que abrirla cuesta milisegundos y siempre
     * arranca de cero. Para tickets sueltos, lo simple gana.
     */
    @SuppressLint("MissingPermission")
    fun print(bytes: ByteArray) {
        val dispositivo = buscarImpresora()
            ?: throw PrinterException(
                "No se encontró la impresora integrada. Revisá que el Bluetooth esté " +
                    "encendido y que la app tenga permiso de dispositivos cercanos."
            )

        var socket: BluetoothSocket? = null
        try {
            socket = dispositivo.createRfcommSocketToServiceRecord(SPP)
            socket.connect()
            socket.outputStream.apply {
                write(bytes)
                flush()
            }
            // Respiro antes de cortar: si se cierra el socket en el mismo instante,
            // la impresora puede descartar lo último que le llegó.
            Thread.sleep(300)
            Log.i(TAG, "Impresos ${bytes.size} bytes")
        } catch (e: SecurityException) {
            throw PrinterException("Falta el permiso de dispositivos cercanos")
        } catch (e: Exception) {
            throw PrinterException(e.message ?: "No se pudo imprimir")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Pulso a la gaveta de dinero por el conector de la impresora. */
    fun abrirGaveta() = print(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()))

    /**
     * Ticket de prueba armado acá adentro.
     *
     * Es lo único que el agente genera por su cuenta: sirve para saber si la
     * impresora responde sin depender de que UcoBot esté al alcance, por ejemplo
     * mientras se está instalando y todavía no se vinculó.
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
        linea(java.text.SimpleDateFormat("dd/MM/yy HH:mm:ss", java.util.Locale("es", "AR"))
            .format(java.util.Date()))
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
