package com.codea.ucobot.agent

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * El timbre: un WebSocket a Supabase Realtime que avisa "hay trabajo".
 *
 * Es el mismo mecanismo que usa el agente de Windows, y por el mismo motivo:
 * preguntarle al servidor cada pocos segundos costaría cientos de miles de
 * requests por mes por cada equipo instalado. Acá la conexión la mantiene
 * Supabase, no nuestro servidor, y sólo se consulta cuando hay algo real.
 *
 * Se habla el protocolo de Phoenix a mano en vez de sumar el SDK de Supabase:
 * son tres tipos de mensaje (unirse, latir, recibir) y evita arrastrar una
 * librería entera a un APK que tiene que instalarse en un POSNET.
 *
 * Por el canal NO viaja ningún dato: el mensaje es {"ring":1} y nada más. El
 * trabajo real lo busca el agente por HTTPS con su token.
 *
 * LA REGLA DE ESTA CLASE: NUNCA SE RINDE.
 * La versión anterior tenía un camino sin vuelta: si al intentar conectar no
 * encontraba los datos de Realtime, lo anotaba y no volvía a intentar nunca más.
 * Eso pasaba justo después de un desvinculado (que borra esos datos) seguido de
 * un revinculado con el servicio ya corriendo — que no vuelve a pasar por
 * onCreate. El equipo quedaba latiendo, imprimiendo por la consulta de respaldo
 * con medio minuto de demora, y mostrando "Reconectando..." para siempre.
 *
 * Ahora cualquier falla agenda un reintento, y además el servicio llama a
 * [asegurarConexion] en cada latido: si por lo que sea quedó caído, a los 30
 * segundos se levanta solo.
 *
 * CONCURRENCIA. Cada conexión nueva sube [generacion], y cada callback de OkHttp
 * compara la suya antes de tocar nada. Sin eso, el aviso tardío de un socket
 * viejo marcaba como caído al socket nuevo que ya estaba andando.
 */
class Doorbell(private val onRing: () -> Unit) {

    private val TAG = "UcoBotDoorbell"

    private val client = OkHttpClient.Builder()
        // Ping propio de OkHttp: mantiene viva la conexión a través del NAT del
        // router del local, que suele cortar lo que no habla por un rato.
        // Verificado contra Supabase: los pongs vuelven en ~200 ms.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /** Todo el estado de abajo se toca sólo con este candado tomado. */
    private val lock = Any()

    private var ws: WebSocket? = null
    private var generacion = 0
    private var latido: Thread? = null
    private var reintento: Thread? = null
    private var esperaMs = ESPERA_INICIAL_MS
    private var inicioIntentoMs = 0L
    private var detenido = true

    /** Con qué datos se abrió la conexión actual, para notar si cambiaron. */
    private var firmaActual: String? = null

    @Volatile private var conectado = false
    @Volatile private var conectando = false
    @Volatile private var ultimaRespuestaMs = 0L

    /** Por qué está caído, en palabras. Viaja en el latido y se ve en el panel. */
    @Volatile var ultimoError: String? = null
        private set

    /** True sólo cuando el servidor CONFIRMÓ la unión al canal, no al abrir el socket. */
    val estaConectado: Boolean get() = conectado

    companion object {
        private const val ESPERA_INICIAL_MS = 2_000L
        private const val ESPERA_MAXIMA_MS = 30_000L

        /** Latido del protocolo Phoenix; Supabase corta al minuto sin esto. */
        private const val LATIDO_PHOENIX_MS = 25_000L

        /**
         * Si en este tiempo no llegó NADA (ni la respuesta a los latidos), el
         * socket es un zombi: figura abierto pero el router ya lo cortó.
         */
        private const val SILENCIO_MAXIMO_MS = 70_000L

        /** Un intento que no terminó de unirse en este tiempo se da por perdido. */
        private const val INTENTO_MAXIMO_MS = 20_000L
    }

    /** Arranca el timbre. Si ya estaba andando con los mismos datos, no hace nada. */
    fun connect() = asegurarConexion()

    /**
     * Deja el timbre andando. Es idempotente y barata: el servicio la llama en
     * cada latido y cada vez que alguien lo arranca, sin preguntar el estado.
     *
     * - Si los datos de Realtime cambiaron (revinculado, clave rotada), reconecta.
     * - Si está conectado pero hace rato que no llega nada, reconecta.
     * - Si hay un intento en curso razonable o un reintento agendado, espera.
     * - Si no, abre ya.
     */
    fun asegurarConexion() {
        synchronized(lock) {
            detenido = false
            val ahora = System.currentTimeMillis()

            if (Config.realtimeFirma() != firmaActual) {
                Log.i(TAG, "Datos de Realtime nuevos: se reconecta")
                esperaMs = ESPERA_INICIAL_MS
                cancelarReintento()
                abrir()
                return
            }

            if (conectado) {
                if (ahora - ultimaRespuestaMs > SILENCIO_MAXIMO_MS) {
                    ultimoError = "Sin respuesta del servidor"
                    Log.w(TAG, "Timbre zombi: se reconecta")
                    caido(ws)
                }
                return
            }

            if (conectando && ahora - inicioIntentoMs < INTENTO_MAXIMO_MS) return
            if (reintento != null) return
            abrir()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            detenido = true
            cancelarReintento()
            cerrarActual("cierre normal")
            firmaActual = null
        }
    }

    // --- Internos: todos se llaman con `lock` tomado ------------------------

    private fun abrir() {
        cerrarActual("reconexión")

        val url = Config.realtimeUrl?.trim()
        val key = Config.realtimeKey?.trim()
        val canal = Config.realtimeChannel?.trim()
        firmaActual = Config.realtimeFirma()

        if (url.isNullOrEmpty() || key.isNullOrEmpty() || canal.isNullOrEmpty()) {
            // Antes acá se volvía sin agendar nada, y el timbre quedaba muerto
            // para siempre. Ahora se reintenta: los datos llegan con el próximo
            // latido o con el revinculado.
            ultimoError = "Sin datos de Realtime"
            programarReintento()
            return
        }

        val wsUrl = url.replace("https://", "wss://").trimEnd('/') +
            "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

        val req = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            ultimoError = "Dirección de Realtime inválida"
            programarReintento()
            return
        }

        val gen = ++generacion
        conectando = true
        inicioIntentoMs = System.currentTimeMillis()
        ws = client.newWebSocket(req, Oyente(gen, "realtime:$canal"))
    }

    /** Cierra la conexión actual (si hay) e invalida todos sus callbacks. */
    private fun cerrarActual(motivo: String) {
        generacion++
        conectado = false
        conectando = false
        detenerLatido()
        val actual = ws
        ws = null
        if (actual != null) {
            try {
                actual.close(1000, motivo)
            } catch (_: Exception) {
            }
        }
    }

    /** La conexión actual murió: se descarta y se agenda otra. */
    private fun caido(socket: WebSocket?) {
        generacion++
        conectado = false
        conectando = false
        detenerLatido()
        if (socket != null) {
            try {
                socket.cancel()
            } catch (_: Exception) {
            }
        }
        ws = null
        programarReintento()
    }

    /**
     * Un único reintento agendado a la vez, con espera creciente (2s, 4s, 8s...
     * hasta 30s). La espera vuelve a 2s en cuanto una unión sale bien.
     */
    private fun programarReintento() {
        if (detenido || reintento != null) return
        val espera = esperaMs
        esperaMs = minOf(esperaMs * 2, ESPERA_MAXIMA_MS)

        val hilo = Thread {
            var seguir = true
            try {
                Thread.sleep(espera)
            } catch (_: InterruptedException) {
                seguir = false
            }
            if (seguir) {
                synchronized(lock) {
                    // Si mientras dormía lo cancelaron y agendaron otro, este ya no manda.
                    if (reintento === Thread.currentThread()) {
                        reintento = null
                        if (!detenido && !conectado) abrir()
                    }
                }
            }
        }
        hilo.isDaemon = true
        reintento = hilo
        hilo.start()
    }

    private fun cancelarReintento() {
        val hilo = reintento
        reintento = null
        hilo?.interrupt()
    }

    private fun arrancarLatido(webSocket: WebSocket, gen: Int) {
        detenerLatido()
        val hilo = Thread {
            var ref = 2
            try {
                while (true) {
                    Thread.sleep(LATIDO_PHOENIX_MS)
                    var cortar = false
                    synchronized(lock) {
                        if (gen != generacion) {
                            cortar = true
                        } else if (System.currentTimeMillis() - ultimaRespuestaMs > SILENCIO_MAXIMO_MS) {
                            ultimoError = "Sin respuesta del servidor"
                            Log.w(TAG, "Timbre sin respuesta: se reconecta")
                            caido(webSocket)
                            cortar = true
                        } else {
                            val hb = JSONObject()
                                .put("topic", "phoenix")
                                .put("event", "heartbeat")
                                .put("payload", JSONObject())
                                .put("ref", ref.toString())
                            ref++
                            webSocket.send(hb.toString())
                        }
                    }
                    if (cortar) break
                }
            } catch (_: InterruptedException) {
            }
        }
        hilo.isDaemon = true
        latido = hilo
        hilo.start()
    }

    private fun detenerLatido() {
        val hilo = latido
        latido = null
        // Puede llamarse desde el propio hilo del latido (cuando detecta un
        // zombi): interrumpirse a sí mismo no hace falta, ya sale del bucle.
        if (hilo != null && hilo !== Thread.currentThread()) hilo.interrupt()
    }

    private inner class Oyente(
        private val gen: Int,
        private val tema: String
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (gen != generacion) {
                    webSocket.close(1000, "conexión vieja")
                    return
                }
                ultimaRespuestaMs = System.currentTimeMillis()

                // Unirse al canal. El tema lleva el prefijo "realtime:" por el
                // protocolo; el resto es el nombre secreto que nos dio el pareo.
                // Todavía NO se marca como conectado: eso espera la respuesta.
                val join = JSONObject()
                    .put("topic", tema)
                    .put("event", "phx_join")
                    .put(
                        "payload",
                        JSONObject().put(
                            "config",
                            JSONObject().put("broadcast", JSONObject().put("self", false))
                        )
                    )
                    .put("ref", "1")
                webSocket.send(join.toString())
                arrancarLatido(webSocket, gen)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }

            var sonar = false
            synchronized(lock) {
                if (gen != generacion) return
                ultimaRespuestaMs = System.currentTimeMillis()

                val evento = msg.optString("event")
                val topic = msg.optString("topic")

                if (evento == "phx_reply" && topic == tema && msg.optString("ref") == "1") {
                    val estado = msg.optJSONObject("payload")?.optString("status")
                    if (estado == "ok") {
                        conectado = true
                        conectando = false
                        esperaMs = ESPERA_INICIAL_MS
                        ultimoError = null
                        Log.i(TAG, "Timbre conectado")
                        // Pudo haber quedado trabajo esperando mientras estaba caído.
                        sonar = true
                    } else {
                        ultimoError = "El canal rechazó la conexión"
                        Log.w(TAG, "Unión rechazada: $text")
                        caido(webSocket)
                    }
                } else if ((evento == "phx_error" || evento == "phx_close") && topic == tema) {
                    // Supabase cierra los canales cuando reinicia el servicio, y el
                    // socket puede quedar abierto igual: sin esto quedaba sordo.
                    ultimoError = "El servidor cerró el canal"
                    Log.w(TAG, "Canal cerrado por el servidor ($evento)")
                    caido(webSocket)
                } else if (evento == "broadcast" && topic == tema) {
                    // Cualquier broadcast en nuestro canal es el timbre. No se mira
                    // el contenido a propósito: el mensaje no trae datos.
                    sonar = true
                }
            }

            // Fuera del candado: procesar trabajos no tiene por qué esperarlo.
            if (sonar) {
                Log.i(TAG, "Timbre: hay trabajo")
                onRing()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // El servidor pidió cerrar: se contesta enseguida para que OkHttp
            // termine y avise por onClosed, en vez de esperar su propio timeout.
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (gen != generacion) return
                if (ultimoError == null) ultimoError = "Conexión cerrada ($code)"
                caido(null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (gen != generacion) return
                ultimoError = if (response != null) "HTTP ${response.code}" else (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "Timbre caído: $ultimoError")
                caido(null)
            }
        }
    }
}
