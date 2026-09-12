package com.codea.ucobot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * El corazón del agente: un servicio en primer plano que no muere.
 *
 * POR QUÉ EN PRIMER PLANO. Android congela y mata sin aviso todo lo que corre de
 * fondo — es la razón por la que "dejar la app abierta" no alcanza: apenas se
 * apaga la pantalla o el cajero pasa a otra app, el sistema le corta la red. Un
 * servicio en primer plano, con su notificación fija, es el único mecanismo que
 * Android respeta de verdad. Es lo mismo que hace Spotify para seguir sonando.
 *
 * La notificación no es un adorno ni un requisito burocrático: es el trato con el
 * sistema operativo. Sin ella, este agente sería un juguete.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "UcoBotAgent"
        private const val CANAL = "ucobot_agent"
        private const val ID_NOTIFICACION = 1

        const val ACCION_DESPERTAR = "com.codea.ucobot.agent.DESPERTAR"

        /** Cada cuánto late contra el servidor. */
        private const val LATIDO_MS = 30_000L

        /** Consulta de reserva por si el timbre se cayó sin avisar. */
        private const val POLL_RESERVA_MS = 60_000L

        /**
         * Consulta mientras el timbre está caído. Es lo que hace que, pase lo que
         * pase con el WebSocket, un ticket nunca tarde más que esto. Antes el
         * respaldo era de 60 s y en la práctica lo rescataba el latido cada 30 s:
         * de ahí los tickets de medio minuto. Windows ya usaba 5 s.
         */
        private const val POLL_DEGRADADO_MS = 5_000L

        /**
         * Cuántos 401 SEGUIDOS hacen falta para desvincularse.
         *
         * Antes alcanzaba uno. Cuando la base tuvo un problema, el servidor
         * contestó 401 a todos y cada equipo borró su token: se desconectaron
         * todos los locales a la vez y hubo que revincular uno por uno. El
         * servidor ya distingue la falla temporal (503), pero un solo error no
         * puede tener un costo tan alto: tres latidos son 90 segundos, y un
         * equipo revocado de verdad deja de latir igual.
         */
        private const val MAX_401_SEGUIDOS = 3

        @Volatile var corriendo = false
            private set
        @Volatile var timbreConectado = false
            private set
        @Volatile var trabajosHechos = 0
            private set
        @Volatile var trabajosFallidos = 0
            private set
        @Volatile var ultimoError: String? = null
            private set

        fun iniciar(context: Context) {
            val i = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val candado = Mutex()
    private var doorbell: Doorbell? = null
    @Volatile private var seguidos401 = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        crearCanal()
        AlarmaPedidos.crearCanal(this)
        Avisos.crearCanal(this)
        startForeground(ID_NOTIFICACION, construirNotificacion("Conectando..."))
        corriendo = true

        doorbell = Doorbell { procesarTrabajos() }.also { it.connect() }

        scope.launch { cicloLatido() }
        scope.launch { cicloReserva() }

        Log.i(TAG, "Servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DESPERTAR) procesarTrabajos()
        // ESTE ERA EL AGUJERO. Revincular llama a iniciar() con el servicio ya
        // corriendo, y eso NO pasa por onCreate: el timbre, que se había quedado
        // sin datos al desvincular, no volvía a conectar nunca.
        if (Config.isPaired) doorbell?.asegurarConexion()
        // START_STICKY: si Android igual lo mata por memoria, lo vuelve a levantar.
        return START_STICKY
    }

    override fun onDestroy() {
        corriendo = false
        timbreConectado = false
        doorbell?.disconnect()
        // Soltar el socket de la impresora: dejarlo colgado es lo que terminaba
        // trabando el Bluetooth del equipo.
        Printer.desconectar()
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "Servicio detenido")
    }

    // --- Ciclos ------------------------------------------------------------

    private suspend fun cicloLatido() {
        while (true) {
            if (Config.isPaired) {
                try {
                    val r = Api.heartbeat(doorbell)
                    seguidos401 = 0
                    // El latido pudo traer datos de Realtime nuevos, y además es la
                    // red de seguridad: si el timbre quedó caído por lo que sea, acá
                    // se levanta. Nunca más un equipo sordo hasta revincularlo.
                    doorbell?.asegurarConexion()
                    timbreConectado = doorbell?.estaConectado == true
                    actualizarNotificacion()
                    // Si el servidor dice que hay trabajo esperando, el timbre no
                    // llegó: se agarra ahora en vez de esperar al próximo ciclo.
                    if (r.optInt("pending", 0) > 0) procesarTrabajos()

                    // Versión nueva: se baja sola y queda una notificación
                    // esperando. Instalarla necesita un toque humano, porque
                    // Android no deja que una app se reemplace sin confirmación.
                    r.optJSONObject("update")?.let { u ->
                        Updater.revisar(
                            this@AgentService,
                            u.optString("version"),
                            u.optString("url")
                        )
                    }
                } catch (e: Api.ApiException) {
                    if (e.status == 401) {
                        seguidos401++
                        if (seguidos401 >= MAX_401_SEGUIDOS) {
                            Log.w(TAG, "Equipo revocado desde el dashboard")
                            Config.unpair()
                            seguidos401 = 0
                            actualizarNotificacion()
                        } else {
                            Log.w(TAG, "Token rechazado ($seguidos401 de $MAX_401_SEGUIDOS): se espera")
                        }
                    } else {
                        Log.w(TAG, "Falló el latido: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falló el latido: ${e.message}")
                }
            }
            delay(LATIDO_MS)
        }
    }

    /**
     * Consulta de respaldo, más seguida cuanto peor está el timbre.
     *
     * Con el timbre andando, una consulta por minuto por las dudas. Con el timbre
     * caído, cada 5 segundos: así la demora de un ticket nunca depende de que el
     * WebSocket esté bien. Se mira el estado en cada vuelta y no al empezar la
     * espera, para que una caída en el medio no deje esperando un minuto entero.
     */
    private suspend fun cicloReserva() {
        var ultimaConsulta = 0L
        while (true) {
            delay(POLL_DEGRADADO_MS)
            if (!Config.isPaired) continue

            val conectadoAhora = doorbell?.estaConectado == true
            if (conectadoAhora != timbreConectado) {
                timbreConectado = conectadoAhora
                actualizarNotificacion()
            }

            val ahora = System.currentTimeMillis()
            if (!conectadoAhora || ahora - ultimaConsulta >= POLL_RESERVA_MS) {
                ultimaConsulta = ahora
                procesarTrabajos()
            }
        }
    }

    // --- Trabajo -----------------------------------------------------------

    private fun procesarTrabajos() {
        scope.launch {
            // Un solo procesamiento a la vez: el timbre puede sonar varias veces
            // seguidas y no queremos dos tandas peleándose por la impresora.
            candado.withLock {
                if (!Config.isPaired) return@withLock
                try {
                    var trabajos = Api.claimJobs()
                    while (trabajos.length() > 0) {
                        var huboError = false
                        for (i in 0 until trabajos.length()) {
                            if (!ejecutar(trabajos.getJSONObject(i))) huboError = true
                        }
                        // Un trabajo que falla vuelve a la cola, así que la próxima
                        // consulta lo devuelve enseguida. Sin esta pausa se
                        // reintentaba tres veces en dos segundos: la impresora ni
                        // se enteraba de que le habían dado tiempo a recuperarse.
                        if (huboError) delay(4000)
                        trabajos = Api.claimJobs()
                    }
                } catch (e: Api.ApiException) {
                    // Un 401 acá NO desvincula: lo decide el latido, que cuenta los
                    // seguidos. Desvincular por un error suelto fue lo que dejó a
                    // todos los locales desconectados de golpe.
                    Log.w(TAG, "No se pudo traer trabajo: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo traer trabajo: ${e.message}")
                }
                actualizarNotificacion()
            }
        }
    }

    /** @return true si salió bien. */
    private fun ejecutar(trabajo: JSONObject): Boolean {
        val id = trabajo.getString("id")
        val tipo = trabajo.getString("type")
        val payload = trabajo.optJSONObject("payload") ?: JSONObject()

        try {
            when (tipo) {
                "print.raw" -> {
                    val b64 = payload.optString("data_b64")
                    if (b64.isNullOrBlank()) throw Exception("El trabajo vino sin contenido")
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val copias = payload.optInt("copies", 1).coerceIn(1, 5)
                    repeat(copias) { Printer.print(bytes) }
                    Log.i(TAG, "Impreso ${payload.optString("label", "trabajo")} (${bytes.size} bytes)")
                }
                "cashdrawer.open" -> Printer.abrirGaveta()
                "notify.show" -> Avisos.mostrar(
                    this,
                    payload.optString("title"),
                    payload.optString("message"),
                    payload.optString("link", "/dashboard")
                )
                "order.alert" -> AlarmaPedidos.avisar(
                    this,
                    payload.optString("order_id"),
                    payload.optString("label", "Pedido nuevo esperando confirmación")
                )
                "agent.ping" -> { /* alcanza con contestar que salió bien */ }
                else -> throw Exception("Este equipo no sabe hacer \"$tipo\". Actualizá la app.")
            }
            trabajosHechos++
            ultimoError = null
            reportarSeguro(id, true, null)
            return true
        } catch (e: Exception) {
            trabajosFallidos++
            ultimoError = e.message
            Log.e(TAG, "Falló $tipo: ${e.message}")
            // El mensaje va tal cual al dashboard: tiene que servirle a alguien
            // parado frente a la impresora, no a un programador.
            reportarSeguro(id, false, e.message)
            return false
        }
    }

    private fun reportarSeguro(id: String, ok: Boolean, detalle: String?) {
        try {
            Api.reportResult(id, ok, detalle)
        } catch (e: Exception) {
            // Si no se puede avisar, el trabajo vence solo del lado del servidor.
            Log.w(TAG, "No se pudo reportar el resultado: ${e.message}")
        }
    }

    // --- Notificación ------------------------------------------------------

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(
            CANAL,
            "UcoBot Agent",
            // Baja: la notificación tiene que estar, pero sin sonar ni molestar.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene la impresión de tickets funcionando"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun construirNotificacion(estado: String): Notification {
        val abrir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, EstadoActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CANAL)
            .setContentTitle("UcoBot Agent")
            .setContentText(estado)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun actualizarNotificacion() {
        val estado = when {
            !Config.isPaired -> "Sin vincular: tocá para configurar"
            timbreConectado -> "Listo para imprimir"
            else -> "Reconectando..."
        }
        try {
            getSystemService(NotificationManager::class.java)
                .notify(ID_NOTIFICACION, construirNotificacion(estado))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo actualizar la notificación: ${e.message}")
        }
    }
}
