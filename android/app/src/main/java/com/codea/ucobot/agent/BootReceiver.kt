package com.codea.ucobot.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Vuelve solo después de un reinicio o de una actualización de la app.
 *
 * En un local nadie se acuerda de abrir una app de fondo después de un corte de
 * luz, y el primer síntoma sería una comanda que no salió.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val acciones = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in acciones) return

        Config.init(context)
        // Sin vincular no hay nada que hacer: la pantalla lo va a resolver.
        if (Config.isPaired) AgentService.iniciar(context)
    }
}
