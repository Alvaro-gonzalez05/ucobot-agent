package com.codea.ucobot.agent

import android.content.Context
import android.content.SharedPreferences

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

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
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
