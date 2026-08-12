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
 * Si esto se cae, no pasa nada grave: el servicio igual consulta cada tanto por
 * las dudas, sólo que con más demora.
 */
class Doorbell(private val onRing: () -> Unit) {

    private val TAG = "UcoBotDoorbell"

    private val client = OkHttpClient.Builder()
        // Ping propio de OkHttp: mantiene viva la conexión a través del NAT del
        // router del local, que suele cortar lo que no habla por un rato.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var latido: Thread? = null
    @Volatile private var conectado = false
    @Volatile private var cerradoAProposito = false

    val estaConectado: Boolean get() = conectado

    fun connect() {
        val url = Config.realtimeUrl
        val key = Config.realtimeKey
        val canal = Config.realtimeChannel

        if (url.isNullOrBlank() || key.isNullOrBlank() || canal.isNullOrBlank()) {
            Log.w(TAG, "Sin datos de Realtime: se trabaja sólo por consulta periódica")
            return
        }

        cerradoAProposito = false

        val wsUrl = url.replace("https://", "wss://").trimEnd('/') +
            "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

        val req = Request.Builder().url(wsUrl).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                conectado = true
                Log.i(TAG, "Timbre conectado")

                // Unirse al canal. El tema lleva el prefijo "realtime:" por el
                // protocolo; el resto es el nombre secreto que nos dio el pareo.
                val join = JSONObject()
                    .put("topic", "realtime:$canal")
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

                arrancarLatido(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    // Cualquier broadcast en nuestro canal es el timbre. No se mira
                    // el contenido a propósito: el mensaje no trae datos.
                    if (msg.optString("event") == "broadcast") {
                        Log.i(TAG, "Timbre: hay trabajo")
                        onRing()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mensaje ilegible: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conectado = false
                if (!cerradoAProposito) {
                    Log.w(TAG, "Timbre caído (${t.message}); se reintenta")
                    reintentar()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conectado = false
                if (!cerradoAProposito) reintentar()
            }
        })
    }

    /**
     * Latido del protocolo: sin esto Supabase corta la conexión al minuto por
     * considerarla muerta.
     */
    private fun arrancarLatido(webSocket: WebSocket) {
        latido?.interrupt()
        latido = Thread {
            var ref = 2
            try {
                while (!Thread.currentThread().isInterrupted && conectado) {
                    Thread.sleep(30_000)
                    if (!conectado) break
                    val hb = JSONObject()
                        .put("topic", "phoenix")
                        .put("event", "heartbeat")
                        .put("payload", JSONObject())
                        .put("ref", (ref++).toString())
                    webSocket.send(hb.toString())
                }
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun reintentar() {
        Thread {
            try {
                Thread.sleep(10_000)
                if (!cerradoAProposito && !conectado) connect()
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        cerradoAProposito = true
        conectado = false
        latido?.interrupt()
        latido = null
        try {
            ws?.close(1000, "cierre normal")
        } catch (_: Exception) {
        }
        ws = null
    }
}
