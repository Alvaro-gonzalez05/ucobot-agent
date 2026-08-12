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
    val CAPABILITIES = listOf("print.raw", "cashdrawer.open", "agent.ping")

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

    private fun devicesJson(): JSONObject {
        // Lo que el dashboard muestra como "impresoras de este equipo". En un
        // POSNET siempre es una sola: la integrada.
        val impresora = JSONObject()
            .put("name", "Impresora integrada")
            .put("kind", "system")
            .put("isDefault", true)
            .put("status", if (Printer.disponible()) "ready" else "offline")
        return JSONObject().put("printers", JSONArray().put(impresora))
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

        val r = post("/api/agent/pair", body, auth = false)

        Config.agentId = r.getString("agent_id")
        Config.token = r.getString("token")
        Config.name = r.optString("name", null)

        r.optJSONObject("realtime")?.let {
            Config.realtimeUrl = it.optString("url", null)
            Config.realtimeKey = it.optString("anon_key", null)
            Config.realtimeChannel = it.optString("channel", null)
        }

        return Config.name ?: "Este equipo"
    }

    /** Latido: dice "estoy vivo" y baja la config que el dueño cambió en la web. */
    fun heartbeat(): JSONObject {
        val body = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("platform", "android")
            .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("capabilities", capabilitiesJson())
            .put("devices", devicesJson())

        val r = post("/api/agent/heartbeat", body)

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

    /** Informa cómo salió un trabajo. El mensaje de error se ve en el dashboard. */
    fun reportResult(jobId: String, ok: Boolean, detalle: String? = null) {
        val body = JSONObject().put("ok", ok)
        if (ok) body.put("result", JSONObject().put("detalle", detalle ?: ""))
        else body.put("error", detalle ?: "Error desconocido")
        post("/api/agent/jobs/$jobId/result", body)
    }
}
