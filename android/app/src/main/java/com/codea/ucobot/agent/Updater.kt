package com.codea.ucobot.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Actualización de la app.
 *
 * POR QUÉ NO ES SILENCIOSA COMO EN WINDOWS: Android no deja que una app se
 * reemplace a sí misma sin que un humano lo confirme. Es una decisión del sistema
 * y no hay forma de saltearla salvo siendo app de sistema o teniendo un MDM. Lo
 * más cerca que se puede llegar es lo que hace esto: bajar el APK en silencio y
 * dejar una notificación esperando. Un toque de quien esté en el mostrador y
 * listo.
 *
 * La descarga sí es silenciosa, que es lo que importa: cuando el encargado toca
 * "actualizar", el archivo ya está y la instalación tarda dos segundos en vez de
 * depender de la conexión del local a esa hora.
 */
object Updater {

    private const val TAG = "UcoBotUpdater"
    private const val CANAL = "ucobot_update"
    private const val ID_NOTIFICACION = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    /** Versión ya descargada y lista para instalar, si hay alguna. */
    @Volatile
    var pendiente: String? = null
        private set

    @Volatile
    private var bajando = false

    private fun archivoDe(context: Context, version: String): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        return File(dir, "UcoBotAgent-$version.apk")
    }

    /**
     * Lo llama el latido cuando el servidor avisa que hay una versión nueva.
     * Si ya está bajada, sólo vuelve a mostrar el aviso.
     */
    fun revisar(context: Context, version: String, url: String) {
        if (bajando) return
        if (version == BuildConfig.VERSION_NAME) return

        val archivo = archivoDe(context, version)

        // Menos de 1 MB es una descarga cortada o una página de error: se descarta
        // y se vuelve a bajar. Instalar eso sería peor que no actualizar.
        if (archivo.exists() && archivo.length() > 1_000_000) {
            pendiente = version
            avisar(context, version)
            return
        }

        bajando = true
        Thread {
            try {
                descargar(url, archivo)
                if (archivo.length() < 1_000_000) throw Exception("el archivo bajó incompleto")
                pendiente = version
                avisar(context, version)
                Log.i(TAG, "Versión $version lista para instalar")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo descargar la actualización: ${e.message}")
                try {
                    archivo.delete()
                } catch (_: Exception) {
                }
            } finally {
                bajando = false
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun descargar(url: String, destino: File) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            val cuerpo = res.body ?: throw Exception("respuesta vacía")
            destino.outputStream().use { salida -> cuerpo.byteStream().copyTo(salida) }
        }
    }

    /** El intent que abre el instalador del sistema con el APK bajado. */
    fun intentDeInstalacion(context: Context): Intent? {
        val version = pendiente ?: return null
        val archivo = archivoDe(context, version)
        if (!archivo.exists()) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archivo
        )

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Android 8+ pide un permiso aparte para instalar apps de fuera de la tienda,
     * y no se resuelve con un diálogo: hay que mandar al usuario a Ajustes.
     */
    fun necesitaPermisoDeInstalacion(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return !context.packageManager.canRequestPackageInstalls()
    }

    fun intentDePermiso(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}")
        )

    private fun avisar(context: Context, version: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CANAL,
                        "Actualizaciones de UcoBot Agent",
                        // Default y no LOW: esta sí tiene que verse, porque sin el
                        // toque del usuario la actualización no pasa nunca.
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }

            // Abre la app en vez del instalador directo: así, si falta el permiso
            // de "instalar apps desconocidas", la pantalla lo explica en vez de
            // que el sistema rebote sin decir nada.
            val abrir = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val n = android.app.Notification.Builder(context, CANAL)
                .setContentTitle("Hay una versión nueva de UcoBot Agent")
                .setContentText("Tocá para actualizar a la $version")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()

            nm.notify(ID_NOTIFICACION, n)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo avisar de la actualización: ${e.message}")
        }
    }
}
