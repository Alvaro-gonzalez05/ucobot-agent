package com.codea.ucobot.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Config del agente en disco.
 *
 * Es el equivalente del agent.json de Windows: guarda la vinculación (id y token)
 * y los datos para conectarse al timbre. Sobrevive a que se cierre la app y a que
 * se reinicie el equipo; sólo se borra si desinstalan o desvinculan.
 */
object Config {
    private const val ARCHIVO = "ucobot_agent"

    private lateinit var prefs: SharedPreferences

    /**
     * Identificador fijo del equipo, para que revincular no cree un registro nuevo
     * en el panel cada vez (ver scripts/165_app_android.sql).
     *
     * ANDROID_ID sobrevive a reinstalar la app y a reiniciar el equipo; cambia
     * sólo con un reseteo de fábrica, que en la práctica es un equipo nuevo.
     */
    var deviceId: String? = null
        private set

    @SuppressLint("HardwareIds")
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        if (deviceId == null) {
            deviceId = try {
                Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
        }
    }

    var serverUrl: String
        get() = prefs.getString("serverUrl", "https://chatbot-sass-eight.vercel.app")!!
        set(v) = prefs.edit().putString("serverUrl", v.trimEnd('/')).apply()

    var agentId: String?
        get() = prefs.getString("agentId", null)
        set(v) = prefs.edit().putString("agentId", v).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    var name: String?
        get() = prefs.getString("name", null)
        set(v) = prefs.edit().putString("name", v).apply()

    /** Datos del timbre: url de Supabase, anon key y nombre del canal. */
    var realtimeUrl: String?
        get() = prefs.getString("realtimeUrl", null)
        set(v) = prefs.edit().putString("realtimeUrl", v).apply()

    var realtimeKey: String?
        get() = prefs.getString("realtimeKey", null)
        set(v) = prefs.edit().putString("realtimeKey", v).apply()

    var realtimeChannel: String?
        get() = prefs.getString("realtimeChannel", null)
        set(v) = prefs.edit().putString("realtimeChannel", v).apply()

    /** Ancho de papel en mm. El iMin Swift 2 Pro es de 58. */
    var ticketWidth: Int
        get() = prefs.getInt("ticketWidth", 58)
        set(v) = prefs.edit().putInt("ticketWidth", v).apply()

    val isPaired: Boolean
        get() = !agentId.isNullOrBlank() && !token.isNullOrBlank()

    /**
     * Huella de los datos de Realtime. El timbre la compara con la de su conexión
     * para darse cuenta de que tiene que reconectar: revinculado, clave rotada,
     * o datos que se borraron con un desvinculado.
     */
    fun realtimeFirma(): String =
        "${realtimeUrl?.trim()}|${realtimeKey?.trim()}|${realtimeChannel?.trim()}"

    fun unpair() {
        prefs.edit()
            .remove("agentId")
            .remove("token")
            .remove("realtimeUrl")
            .remove("realtimeKey")
            .remove("realtimeChannel")
            .apply()
    }
}
