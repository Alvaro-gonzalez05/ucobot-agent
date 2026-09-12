package com.codea.ucobot.agent

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Una ventana nueva que la web abre (window.open), adentro de la app.
 *
 * POR QUÉ ADENTRO. Conectar Mercado Pago abre una ventana que empieza y termina
 * en UcoBot (/api/mp/oauth/start y su callback), y los dos pasos exigen la sesión
 * iniciada. Mandarla al navegador del sistema la rompía: Chrome no tiene la
 * sesión de la app y terminaba en el login. Esta ventana es otra WebView de la
 * misma app, así que comparte las cookies — la sesión está — y además es una
 * ventana "de verdad" para la web: `window.opener.postMessage` y `window.close()`
 * funcionan, que es como la tarjeta de Mercado Pago se entera de que terminó.
 *
 * POR QUÉ ALGUNAS VAN A CHROME. Meta (Facebook, Instagram, WhatsApp) bloquea su
 * login dentro de las apps. Esas ventanas se abren en el navegador; los flujos
 * que dependen de ellas ya saben volver solos (ver lib/meta/oauth-state.ts y la
 * tarjeta de WhatsApp, que revisa los números al volver a la app).
 */
class VentanaEmergente(
    private val actividad: MainActivity,
    userAgent: String
) {

    companion object {
        /** Logins que se niegan a funcionar dentro de una app: van al navegador. */
        private val DEL_NAVEGADOR = listOf(
            "facebook.com", "fb.com", "meta.com", "instagram.com", "whatsapp.com", "ycloud.com"
        )

        fun vaAlNavegador(uri: Uri): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return DEL_NAVEGADOR.any { host == it || host.endsWith(".$it") }
        }
    }

    /** La WebView que la ventana nueva usa. Se entrega en onCreateWindow. */
    val web: WebView = WebView(actividad)

    private var dialogo: Dialog? = null
    private var titulo: TextView? = null
    private var decidida = false
    private var destruida = false

    init {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Un popup adentro de este popup se carga acá mismo.
            setSupportMultipleWindows(false)
            userAgentString = userAgent
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val esquema = uri.scheme?.lowercase()
                if (esquema !in setOf("http", "https", "blob", "data", "about")) {
                    // Mercado Pago intenta abrir su propia app (intent://,
                    // mercadopago://). Eso sacaría a la persona del flujo: se usa
                    // la versión web si la ofrece, y si no se sigue acá.
                    abrirVersionWeb(uri)
                    return true
                }
                if (request.isForMainFrame && !decidida) {
                    decidir(uri)
                    return vaAlNavegador(uri)
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!decidida && !url.isNullOrBlank() && url != "about:blank") decidir(Uri.parse(url))
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                // La página pidió cerrarse (window.close): la de Mercado Pago lo hace
                // después de avisarle a UcoBot que la conexión salió.
                cerrar()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                titulo?.text = title ?: ""
            }
        }
    }

    /** Con la primera dirección se decide: adentro de la app, o al navegador. */
    private fun decidir(uri: Uri) {
        decidida = true
        if (vaAlNavegador(uri)) {
            web.stopLoading()
            actividad.abrirAfuera(uri)
            web.post { cerrar() }
        } else {
            mostrar()
        }
    }

    private fun abrirVersionWeb(uri: Uri) {
        if (uri.scheme == "intent") {
            val alternativa = try {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (e: Exception) {
                null
            }
            if (!alternativa.isNullOrBlank()) web.loadUrl(alternativa)
        }
    }

    private fun mostrar() {
        if (dialogo != null || destruida) return

        val oscuro = ContextCompat.getColor(actividad, R.color.ucobot_oscuro)
        val verde = ContextCompat.getColor(actividad, R.color.ucobot_verde)

        val barra = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(oscuro)
            setPadding(dp(16), 0, dp(8), 0)
        }
        val textoTitulo = TextView(actividad).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titulo = textoTitulo
        val botonCerrar = TextView(actividad).apply {
            text = "Cerrar"
            setTextColor(verde)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { cerrar() }
        }
        barra.addView(textoTitulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(botonCerrar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val contenedor = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        contenedor.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        (web.parent as? ViewGroup)?.removeView(web)
        contenedor.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nuevo = Dialog(actividad, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        nuevo.setContentView(contenedor)
        nuevo.setOnDismissListener { destruir() }
        nuevo.setOnKeyListener { _, codigo, evento ->
            if (codigo != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            // Atrás navega adentro de la ventana; en la primera página, la cierra.
            if (evento.action == KeyEvent.ACTION_UP) {
                if (web.canGoBack()) web.goBack() else cerrar()
            }
            true
        }
        dialogo = nuevo
        nuevo.show()
        nuevo.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun cerrar() {
        val d = dialogo
        if (d != null && d.isShowing) d.dismiss() else destruir()
    }

    private fun destruir() {
        if (destruida) return
        destruida = true
        dialogo = null
        try {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Exception) {
        }
    }

    private fun dp(valor: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), actividad.resources.displayMetrics).toInt()
}
