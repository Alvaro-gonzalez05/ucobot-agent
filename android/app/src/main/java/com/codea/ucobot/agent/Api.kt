package com.codea.ucobot.agent

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP contra UcoBot.
 *
 * Habla exactamente los mismos endpoints que el agente de Windows: el servidor no
 * distingue si del otro lado hay una PC o un POSNET, y no hubo que tocarle una
 * línea para sumar Android.
 *
 * Todo sale del equipo hacia afuera: no abre ningún puerto y funciona detrás de
 * cualquier red del local.
 */
object Api {

    /** Lo que este agente sabe hacer. El servidor filtra los trabajos por esto. */
    val CAPABILITIES = listOf("print.raw", "cashdrawer.open", "agent.ping", "order.alert", "notify.show")

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class ApiException(val status: Int, message: String) : Exception(message)

    private fun post(path: String, body: JSONObject, auth: Boolean = true): JSONObject {
        val req = Request.Builder()
            .url(Config.serverUrl + path)
            .post(body.toString().toRequestBody(JSON))
            .apply { if (auth) header("Authorization", "Bearer ${Config.token}") }
            .build()

        client.newCall(req).execute().use { res ->
            val texto = res.body?.string().orEmpty()
            val json = try {
                if (texto.isBlank()) JSONObject() else JSONObject(texto)
            } catch (e: Exception) {
                JSONObject()
            }
            if (!res.isSuccessful) {
                throw ApiException(res.code, json.optString("error", "HTTP ${res.code}"))
            }
            return json
        }
    }

    private fun capabilitiesJson() = JSONArray().apply { CAPABILITIES.forEach { put(it) } }

    private fun devicesJson(timbre: Doorbell? = null): JSONObject {
        // Lo que el dashboard muestra como "impresoras de este equipo". En un
        // POSNET siempre es una sola: la integrada.
        val impresora = JSONObject()
            .put("name", "Impresora integrada")
            .put("kind", "system")
            .put("isDefault", true)
            .put("status", if (Printer.disponible()) "ready" else "offline")
        val devices = JSONObject().put("printers", JSONArray().put(impresora))
        // Si late pero el timbre está caído, cada ticket espera a la consulta de
        // respaldo. Sin este dato el panel decía "Conectada" y eso era invisible.
        if (timbre != null) {
            devices.put(
                "doorbell",
                JSONObject()
                    .put("connected", timbre.estaConectado)
                    .put("error", timbre.ultimoError ?: JSONObject.NULL)
            )
        }
        return devices
    }

    /** Un texto del JSON, o null si falta, es null o está vacío. */
    private fun texto(o: JSONObject, campo: String): String? =
        if (o.isNull(campo)) null else o.optString(campo).trim().ifEmpty { null }

    /**
     * Guarda los datos del timbre si vinieron completos.
     *
     * Viene al vincular y, desde el servidor nuevo, en cada latido. Si falta
     * alguno no se toca lo guardado: un servidor viejo que no los manda no puede
     * dejar al agente sin timbre.
     */
    private fun guardarRealtime(r: JSONObject) {
        val rt = r.optJSONObject("realtime") ?: return
        val url = texto(rt, "url") ?: return
        val key = texto(rt, "anon_key") ?: return
        val canal = texto(rt, "channel") ?: return
        Config.realtimeUrl = url
        Config.realtimeKey = key
        Config.realtimeChannel = canal
    }

    /** Canjea el código de vinculación por el token definitivo. */
    fun pair(code: String): String {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("platform", "android")
            .put("version", BuildConfig.VERSION_NAME)
            .put("capabilities", capabilitiesJson())
            .put("devices", devicesJson())
            .put("device_id", Config.deviceId ?: JSONObject.NULL)

        val r = post("/api/agent/pair", body, auth = false)

        Config.agentId = r.getString("agent_id")
        Config.token = r.getString("token")
        Config.name = r.optString("name", null)

        guardarRealtime(r)

        return Config.name ?: "Este equipo"
    }

    /** Latido: dice "estoy vivo" y baja la config que el dueño cambió en la web. */
    fun heartbeat(timbre: Doorbell? = null): JSONObject {
        val body = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("platform", "android")
            .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("capabilities", capabilitiesJson())
            .put("devices", devicesJson(timbre))

        val r = post("/api/agent/heartbeat", body)
        guardarRealtime(r)

        r.optJSONObject("settings")?.let { s ->
            if (s.has("ticketWidth")) Config.ticketWidth = s.optInt("ticketWidth", 58)
        }
        return r
    }

    /** Reclama trabajos pendientes. */
    fun claimJobs(limit: Int = 5): JSONArray {
        val body = JSONObject()
            .put("capabilities", capabilitiesJson())
            .put("limit", limit)
        return post("/api/agent/jobs/next", body).optJSONArray("jobs") ?: JSONArray()
    }

    /**
     * De estos pedidos, cuáles siguen sin atender. Lo usa la alarma mientras
     * suena, para callarse en cuanto alguien los confirma desde cualquier equipo.
     */
    fun pedidosPendientes(ids: Collection<String>): Set<String> {
        val lista = JSONArray().apply { ids.forEach { put(it) } }
        val r = post("/api/agent/alerts", JSONObject().put("order_ids", lista))
        val pendientes = r.optJSONArray("pending") ?: return ids.toSet()
        return (0 until pendientes.length()).map { pendientes.getString(it) }.toSet()
    }

    /** Informa cómo salió un trabajo. El mensaje de error se ve en el dashboard. */
    fun reportResult(jobId: String, ok: Boolean, detalle: String? = null) {
        val body = JSONObject().put("ok", ok)
        if (ok) body.put("result", JSONObject().put("detalle", detalle ?: ""))
        else body.put("error", detalle ?: "Error desconocido")
        post("/api/agent/jobs/$jobId/result", body)
    }
}
