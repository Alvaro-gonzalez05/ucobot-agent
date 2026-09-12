package com.codea.ucobot.agent

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Lo que la web le puede pedir a la app.
 *
 * La web manda {"id", "method", "params"} y la app contesta {"id", "ok",
 * "result" | "error"}. Del lado web vive en lib/native-app.ts.
 *
 * SEGURIDAD. El objeto `UcoBotNative` sólo se publica en páginas del dominio de
 * UcoBot, con la regla de origen de androidx.webkit: un link de afuera que llegue
 * a cargarse no lo ve, y no puede pedir imprimir ni vincular el equipo. Se
 * descarta addJavascriptInterface como mecanismo principal justamente porque lo
 * expone a cualquier página. Queda sólo como respaldo para WebViews viejas, y ahí
 * se revisa el origen en cada llamada.
 */
class Puente(private val actividad: MainActivity, private val web: WebView) {

    private val TAG = "UcoBotPuente"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val principal = Handler(Looper.getMainLooper())

    fun instalar() {
        val propio = Uri.parse(Config.serverUrl)
        val origen = "${propio.scheme}://${propio.host}"

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "UcoBotNative", setOf(origen)) { _, mensaje, _, esMarcoPrincipal, respuesta ->
                // Un iframe de otro lado adentro de UcoBot tampoco puede usarlo.
                if (esMarcoPrincipal) {
                    val texto = mensaje.data
                    if (texto != null) atender(texto) { json -> respuesta.postMessage(json) }
                }
            }
        } else {
            Log.w(TAG, "WebView sin WEB_MESSAGE_LISTENER: se usa el puente de respaldo")
            web.addJavascriptInterface(Respaldo(), "UcoBotNativeLegacy")
        }
    }

    fun cerrar() {
        scope.cancel()
    }

    /** Puente para WebViews viejas: revisa el origen en cada llamada. */
    private inner class Respaldo {
        @JavascriptInterface
        fun postMessage(texto: String) {
            principal.post {
                val url = web.url
                if (url == null || !actividad.esDeUcoBot(Uri.parse(url))) {
                    Log.w(TAG, "Pedido al puente desde fuera de UcoBot: ignorado")
                } else {
                    atender(texto) { json ->
                        web.evaluateJavascript(
                            "window.__ucobotNativeReply&&window.__ucobotNativeReply(${JSONObject.quote(json)})",
                            null
                        )
                    }
                }
            }
        }
    }

    /** Se llama en el hilo principal; la respuesta también sale por el principal. */
    private fun atender(texto: String, responder: (String) -> Unit) {
        val pedido = try {
            JSONObject(texto)
        } catch (e: Exception) {
            return
        }
        val id = pedido.optString("id")
        val metodo = pedido.optString("method")
        val params = pedido.optJSONObject("params") ?: JSONObject()

        scope.launch {
            val salida = JSONObject().put("id", id)
            try {
                val resultado = withContext(Dispatchers.IO) { ejecutar(metodo, params) }
                salida.put("ok", true).put("result", resultado ?: JSONObject.NULL)
            } catch (e: Exception) {
                Log.w(TAG, "Falló $metodo: ${e.message}")
                salida.put("ok", false).put("error", e.message ?: "La app no pudo hacerlo")
            }
            responder(salida.toString())
        }
    }

    /** Corre en un hilo de fondo: puede imprimir o ir a la red. */
    private fun ejecutar(metodo: String, params: JSONObject): Any? {
        return when (metodo) {
            "info" -> JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("paired", Config.isPaired)
                .put("agentId", Config.agentId ?: JSONObject.NULL)
                .put("deviceId", Config.deviceId ?: JSONObject.NULL)
                .put("agentName", Config.name ?: JSONObject.NULL)
                .put("ticketWidth", Config.ticketWidth)
                .put("printerReady", Printer.disponible())
                .put("doorbell", AgentService.timbreConectado)

            "pair" -> {
                val codigo = params.optString("code").trim()
                if (codigo.isEmpty()) throw Exception("Falta el código de vinculación")
                val nombre = Api.pair(codigo)
                AgentService.iniciar(actividad)
                actividad.runOnUiThread { actividad.pedirExencionDeBateria() }
                JSONObject()
                    .put("agentId", Config.agentId ?: JSONObject.NULL)
                    .put("name", nombre)
            }

            "unpair" -> {
                Config.unpair()
                null
            }

            "print" -> {
                val b64 = params.optString("data_b64")
                if (b64.isEmpty()) throw Exception("El ticket vino vacío")
                Printer.print(Base64.decode(b64, Base64.DEFAULT))
                null
            }

            "openDrawer" -> {
                Printer.abrirGaveta()
                null
            }

            "openStatus" -> {
                actividad.runOnUiThread {
                    actividad.startActivity(Intent(actividad, EstadoActivity::class.java))
                }
                null
            }

            "saveFile" -> {
                guardarArchivo(
                    params.optString("name"),
                    params.optString("mime"),
                    params.optString("data_b64")
                )
                null
            }

            else -> throw Exception("La app no conoce \"$metodo\". Actualizala.")
        }
    }

    /**
     * Baja un archivo que la web armó en memoria (un blob:). Android no lo puede
     * leer desde afuera, así que se le pide a la propia página que lo lea y se lo
     * pase al puente como "saveFile".
     */
    fun descargarBlob(url: String, nombre: String, mime: String?) {
        val js = "(function(){" +
            "var u=${JSONObject.quote(url)},n=${JSONObject.quote(nombre)},m=${JSONObject.quote(mime ?: "")};" +
            "fetch(u).then(function(r){return r.blob()}).then(function(b){" +
            "var fr=new FileReader();" +
            "fr.onload=function(){var s=String(fr.result);var i=s.indexOf(',');" +
            "var p=window.UcoBotNative||window.UcoBotNativeLegacy;if(!p)return;" +
            "p.postMessage(JSON.stringify({id:'descarga-'+Date.now(),method:'saveFile',params:{name:n,mime:m||b.type,data_b64:s.slice(i+1)}}));};" +
            "fr.readAsDataURL(b);" +
            "}).catch(function(e){console.error('[app] no se pudo bajar el archivo',e)});" +
            "})();"
        web.evaluateJavascript(js, null)
    }

    private fun guardarArchivo(nombre: String, mime: String, b64: String) {
        if (b64.isEmpty()) throw Exception("El archivo vino vacío")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val nombreFinal = nombre.ifBlank { "ucobot-${System.currentTimeMillis()}" }
        val tipo = mime.ifBlank { "application/octet-stream" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = actividad.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nombreFinal)
                put(MediaStore.Downloads.MIME_TYPE, tipo)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw Exception("No se pudo crear el archivo en Descargas")
            val salida = resolver.openOutputStream(uri) ?: throw Exception("No se pudo escribir el archivo")
            salida.use { it.write(bytes) }
            valores.clear()
            valores.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, valores, null, null)
        } else {
            // Android 8 y 9: carpeta de la app, que no pide permisos de almacenamiento.
            val carpeta = actividad.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: actividad.filesDir
            File(carpeta, nombreFinal).writeBytes(bytes)
        }

        actividad.runOnUiThread {
            Toast.makeText(actividad, "Guardado en Descargas: $nombreFinal", Toast.LENGTH_LONG).show()
        }
    }
}
