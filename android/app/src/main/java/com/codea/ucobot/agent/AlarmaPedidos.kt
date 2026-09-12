package com.codea.ucobot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log

/**
 * La alarma de pedidos nuevos cuando UcoBot no está a la vista.
 *
 * POR QUÉ HACE FALTA. La alarma de la web suena mientras la página está viva. En
 * un POSNET con la pantalla apagada, o con el cajero en otra app, Android frena
 * la web y un pedido de WhatsApp entraba en silencio. El servicio de la app, en
 * cambio, nunca se frena: el pedido le llega como un trabajo "order.alert" (ver
 * scripts/165_app_android.sql) y acá se convierte en notificación y sonido.
 *
 * MISMO COMPORTAMIENTO QUE LA WEB. Mismo sonido, cada 1,7 segundos, hasta que el
 * pedido se atiende desde cualquier equipo. Como la app no tiene sesión ni lee la
 * base, mientras suena le pregunta al servidor cada pocos segundos si esos
 * pedidos siguen sin atender (/api/agent/alerts).
 *
 * NUNCA LAS DOS A LA VEZ. Con UcoBot a la vista suena la web; ésta se calla sola
 * (MainActivity avisa). Y respeta la música del local: no pide foco de audio, así
 * que no pausa ni baja lo que esté sonando.
 */
object AlarmaPedidos {

    private const val TAG = "UcoBotAlarma"
    private const val CANAL = "ucobot_pedidos"
    private const val ID_NOTIFICACION = 2000

    /** Mismo intervalo que components/dashboard/alerta-pedidos-bot.tsx. */
    private const val INTERVALO_SONIDO_MS = 1_700L

    /** Cada cuánto se pregunta si los pedidos ya se atendieron. */
    private const val CONSULTA_MS = 6_000L

    /**
     * Tope de seguridad: un pedido que nadie atiende en un cuarto de hora deja
     * de sonar (la notificación queda). Evita una alarma eterna si el servidor
     * no contesta y el local está cerrado.
     */
    private const val DURACION_MAXIMA_MS = 15 * 60_000L

    private val candado = Any()

    /** Pedidos sonando: id → texto de la notificación. */
    private val pedidos = LinkedHashMap<String, String>()
    private var hilo: Thread? = null

    fun crearCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Pedidos nuevos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisa los pedidos que entran por WhatsApp e Instagram mientras esperan confirmación."
            // El sonido lo maneja la alarma, que repite hasta que se atiende. Si el
            // canal también sonara, cada aviso sonaría dos veces.
            setSound(null, null)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    /** Llega un pedido nuevo. Lo llama el servicio al recibir un "order.alert". */
    fun avisar(context: Context, orderId: String, texto: String) {
        if (orderId.isBlank()) return
        val app = context.applicationContext
        synchronized(candado) {
            pedidos[orderId] = texto
            mostrarNotificacion(app)
            // Con UcoBot a la vista ya suena la web: acá sólo queda la notificación.
            if (!MainActivity.visible) arrancar(app)
        }
    }

    /** UcoBot pasó a estar a la vista: suena la web, ésta se calla. */
    fun silenciar() {
        synchronized(candado) {
            hilo?.interrupt()
            hilo = null
        }
    }

    /**
     * UcoBot dejó de estar a la vista. Si quedaron pedidos sin atender (el cajero
     * vio el aviso de la web y mandó la app al fondo sin confirmar), vuelve a
     * sonar acá.
     */
    fun reanudar(context: Context) {
        synchronized(candado) {
            if (pedidos.isNotEmpty() && !MainActivity.visible) arrancar(context.applicationContext)
        }
    }

    private fun arrancar(app: Context) {
        if (hilo != null) return
        val nuevo = Thread { bucle(app) }
        nuevo.isDaemon = true
        hilo = nuevo
        nuevo.start()
    }

    private fun bucle(app: Context) {
        val inicio = System.currentTimeMillis()
        var ultimaConsulta = 0L
        var reproductor: MediaPlayer? = null

        // Sin esto, con la pantalla apagada el procesador se duerme entre sonido
        // y sonido y la alarma se entrecorta o se frena.
        val wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UcoBot:alarma-pedidos")
        wakeLock.acquire(DURACION_MAXIMA_MS + 60_000L)

        try {
            reproductor = crearReproductor(app)
            while (!Thread.currentThread().isInterrupted) {
                val ahora = System.currentTimeMillis()

                if (ahora - ultimaConsulta >= CONSULTA_MS) {
                    ultimaConsulta = ahora
                    if (!quedanPendientes(app)) break
                }
                if (MainActivity.visible) break
                if (ahora - inicio > DURACION_MAXIMA_MS) {
                    Log.w(TAG, "La alarma llegó al tope de tiempo: se calla, la notificación queda")
                    break
                }

                try {
                    reproductor?.seekTo(0)
                    reproductor?.start()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo reproducir el sonido: ${e.message}")
                }
                Thread.sleep(INTERVALO_SONIDO_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            try {
                reproductor?.release()
            } catch (_: Exception) {
            }
            if (wakeLock.isHeld) wakeLock.release()
            synchronized(candado) {
                if (hilo === Thread.currentThread()) hilo = null
            }
        }
    }

    /**
     * Le pregunta al servidor qué pedidos siguen sin atender y saca los que ya no.
     *
     * Ante la duda sigue sonando: si no hay internet o el servidor falla, un
     * pedido que se calla solo es peor que una alarma unos segundos de más.
     */
    private fun quedanPendientes(app: Context): Boolean {
        val ids = synchronized(candado) { pedidos.keys.toList() }
        if (ids.isEmpty()) return false
        val siguen = try {
            Api.pedidosPendientes(ids)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el estado de los pedidos: ${e.message}")
            return true
        }
        synchronized(candado) {
            pedidos.keys.retainAll(siguen)
            if (pedidos.isEmpty()) {
                app.getSystemService(NotificationManager::class.java).cancel(ID_NOTIFICACION)
                return false
            }
            mostrarNotificacion(app)
        }
        return true
    }

    private fun crearReproductor(app: Context): MediaPlayer? {
        val atributos = AudioAttributes.Builder()
            // Por el mismo canal que la música y que la alarma de la web: si el
            // local la escucha, escucha esto. Y sin pedir foco, para no cortarla.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sesion = app.getSystemService(AudioManager::class.java).generateAudioSessionId()
        return MediaPlayer.create(app, R.raw.pedido_bot, atributos, sesion)
    }

    /** Una sola notificación para todos los pedidos esperando. Llamar con el candado. */
    private fun mostrarNotificacion(app: Context) {
        val cantidad = pedidos.size
        if (cantidad == 0) return

        val abrir = PendingIntent.getActivity(
            app,
            ID_NOTIFICACION,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_URL, "${Config.serverUrl}/dashboard/pedidos")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titulo = if (cantidad == 1) "Pedido nuevo" else "$cantidad pedidos nuevos"
        val detalle = if (cantidad == 1) pedidos.values.first() else "Esperando confirmación en UcoBot"

        val notificacion = Notification.Builder(app, CANAL)
            .setContentTitle(titulo)
            .setContentText(detalle)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .build()

        try {
            app.getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, notificacion)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones igual suena; sólo no se ve el aviso.
            Log.w(TAG, "Sin permiso para notificar: ${e.message}")
        }
    }
}
