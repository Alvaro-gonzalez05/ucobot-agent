package com.codea.ucobot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Las notificaciones de UcoBot, como notificaciones de Android.
 *
 * Son las mismas que aparecen en la campanita: "Solicitud de Humano", "Pago
 * recibido", "Nueva reserva", "El bot no pudo responder". En la web llegan por
 * web push, que adentro de la app no existe; acá llegan como un trabajo
 * "notify.show" (ver lib/notifications.ts).
 *
 * Los pedidos nuevos del bot NO pasan por acá: tienen su alarma propia, que
 * suena hasta que se atienden (ver [AlarmaPedidos]).
 */
object Avisos {

    private const val TAG = "UcoBotAvisos"
    private const val CANAL = "ucobot_avisos"

    fun crearCanal(context: Context) {
        // Importancia normal y el sonido del sistema: se escuchan y se ven, pero no
        // interrumpen como la alarma de pedidos.
        val canal = NotificationChannel(CANAL, "Avisos de UcoBot", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Pedidos de atención humana, pagos, reservas y alertas del bot."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    fun mostrar(context: Context, titulo: String, mensaje: String, link: String?) {
        // Con UcoBot a la vista, la campanita ya lo muestra y hace su sonido.
        if (MainActivity.visible) return

        val app = context.applicationContext
        val ruta = if (!link.isNullOrBlank() && link.startsWith("/") && !link.startsWith("//")) link else "/dashboard"
        val id = (titulo + mensaje + System.currentTimeMillis()).hashCode()

        val abrir = PendingIntent.getActivity(
            app,
            id,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_URL, "${Config.serverUrl}$ruta")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = Notification.Builder(app, CANAL)
            .setContentTitle(titulo.ifBlank { "UcoBot" })
            .setContentText(mensaje)
            .setStyle(Notification.BigTextStyle().bigText(mensaje))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()

        try {
            app.getSystemService(NotificationManager::class.java).notify(id, notificacion)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para notificar: ${e.message}")
        }
    }
}
