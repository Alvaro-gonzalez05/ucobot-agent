package com.codea.ucobot.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * UcoBot, adentro de la app.
 *
 * Es la misma web de siempre, cargada de producción: cada deploy llega al
 * instante a todos los equipos, igual que en la PWA. Lo nativo es lo que un
 * navegador no puede hacer, y vive en otras clases:
 *  - [Puente]: la web le pide a la app imprimir, abrir la gaveta, vincularse.
 *  - [AgentService]: los trabajos que llegan de afuera (pedidos de WhatsApp,
 *    tickets mandados desde otra caja) y la alarma de pedidos con la app cerrada.
 *
 * Todo lo de acá son las cosas que una WebView no trae resueltas y que UcoBot
 * usa: subir imágenes, bajar archivos, abrir links de pago afuera, el botón atrás
 * y una pantalla clara cuando no hay internet.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UcoBotApp"

        /** Para abrir la app en una pantalla puntual (la notificación de un pedido). */
        const val EXTRA_URL = "com.codea.ucobot.agent.URL"

        /**
         * Si UcoBot está a la vista. Con la app visible suena la alarma de la web;
         * con la app en segundo plano, la nativa. Nunca las dos.
         */
        @Volatile var visible = false
            private set
    }

    private lateinit var web: WebView
    private lateinit var sinConexion: View
    private lateinit var barraCarga: ProgressBar
    private lateinit var puente: Puente

    private var huboErrorDeCarga = false
    private var archivosPendientes: ValueCallback<Array<Uri>>? = null

    private val elegirArchivos = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        archivosPendientes?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        )
        archivosPendientes = null
    }

    private val pedirPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // En los POSNET suena música: las teclas de volumen tienen que seguir
        // manejándola, y la app no le pide foco de audio a nadie.
        volumeControlStream = AudioManager.STREAM_MUSIC
        Config.init(this)
        window.statusBarColor = ContextCompat.getColor(this, R.color.ucobot_oscuro)

        val raiz = FrameLayout(this)
        web = WebView(this)
        raiz.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        barraCarga = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        raiz.addView(barraCarga, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))

        sinConexion = construirSinConexion()
        raiz.addView(sinConexion, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)

        configurarWeb()

        // EL PUENTE VA ANTES DE CARGAR: androidx.webkit sólo publica el objeto en
        // las páginas que se cargan después de registrarlo.
        puente = Puente(this, web)
        puente.instalar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Atrás navega la web. En la pantalla inicial, la app va al fondo
                // en vez de cerrarse: cerrarla no apaga nada y sólo obliga a
                // volver a cargar.
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        asegurarPermisos()
        if (Config.isPaired) AgentService.iniciar(this)

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(urlPedida(intent) ?: "${Config.serverUrl}/dashboard")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        urlPedida(intent)?.let { web.loadUrl(it) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        // Con la app a la vista suena la web: la nativa se calla.
        AlarmaPedidos.silenciar()
        avisarVisibilidad(true)
    }

    override fun onStop() {
        visible = false
        avisarVisibilidad(false)
        AlarmaPedidos.reanudar(this)
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        puente.cerrar()
        web.destroy()
        super.onDestroy()
    }

    // --- Web ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // La alarma de pedidos no puede esperar a que alguien toque la pantalla.
            mediaPlaybackRequiresUserGesture = false
            // Las ventanas nuevas se atajan en onCreateWindow (links de pago, etc).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            // Así la web sabe que corre adentro de la app incluso antes de que el
            // puente esté listo, y el servidor también lo puede ver.
            userAgentString = "$userAgentString UcoBotApp/${BuildConfig.VERSION_NAME}"
        }

        // Que Android no baje la prioridad de la web apenas la app pasa al fondo:
        // así el aviso en tiempo real de la web sigue vivo un rato más.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (esDeUcoBot(request.url)) return false
                abrirAfuera(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                huboErrorDeCarga = false
                barraCarga.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                barraCarga.visibility = View.GONE
                if (!huboErrorDeCarga) sinConexion.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                huboErrorDeCarga = true
                Log.w(TAG, "No cargó ${request.url}: ${error.description}")
                sinConexion.visibility = View.VISIBLE
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                barraCarga.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                // Sin esto los campos de archivo (logo del ticket, fotos de
                // productos) no hacen nada al tocarlos.
                archivosPendientes?.onReceiveValue(null)
                archivosPendientes = filePathCallback
                return try {
                    elegirArchivos.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    archivosPendientes = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                // Una ventana nueva de verdad, adentro de la app y con la sesión
                // (conectar Mercado Pago la necesita). Si resulta ser un login de
                // Meta, la propia ventana la manda a Chrome. Ver VentanaEmergente.
                val transporte = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val ventana = VentanaEmergente(this@MainActivity, web.settings.userAgentString)
                transporte.webView = ventana.web
                resultMsg.sendToTarget()
                return true
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            descargar(url, contentDisposition, mimeType)
        }
    }

    /** Avisa a la web si la app está a la vista, para que su alarma sepa si sonar. */
    private fun avisarVisibilidad(estaVisible: Boolean) {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "window.__ucobotAppVisible=$estaVisible;window.dispatchEvent(new Event('ucobot-app-visibility'))",
            null
        )
    }

    /**
     * A qué pantalla abrir la app, si alguien lo pidió.
     *
     * Dos caminos: la notificación de un pedido (EXTRA_URL) y el enlace
     * ucobot://abrir?ruta=... con el que Chrome devuelve a la app después de
     * conectar Instagram o Messenger (ver app/abrir-app). Sólo se aceptan rutas
     * de UcoBot: un enlace de afuera no puede hacer que la app cargue otro sitio.
     */
    private fun urlPedida(intent: Intent?): String? {
        intent?.getStringExtra(EXTRA_URL)?.let { url ->
            return if (esDeUcoBot(Uri.parse(url))) url else null
        }
        val datos = intent?.data ?: return null
        if (datos.scheme != "ucobot" || datos.host != "abrir") return null
        val ruta = datos.getQueryParameter("ruta") ?: "/dashboard"
        if (!ruta.startsWith("/") || ruta.startsWith("//")) return null
        return "${Config.serverUrl}$ruta"
    }

    fun esDeUcoBot(uri: Uri): Boolean {
        val propio = Uri.parse(Config.serverUrl)
        return (uri.scheme == "https" || uri.scheme == "http") && uri.host == propio.host
    }

    fun abrirAfuera(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Nadie puede abrir $uri")
        }
    }

    private fun descargar(url: String, contentDisposition: String?, mimeType: String?) {
        val nombre = URLUtil.guessFileName(url, contentDisposition, mimeType)
        // Un archivo que la web armó en memoria (un blob:) no se puede bajar desde
        // afuera: se le pide a la propia página que lo lea y se lo pase al puente.
        if (url.startsWith("blob:")) {
            puente.descargarBlob(url, nombre, mimeType)
            return
        }
        try {
            val pedido = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            CookieManager.getInstance().getCookie(url)?.let { pedido.addRequestHeader("Cookie", it) }
            getSystemService(DownloadManager::class.java).enqueue(pedido)
        } catch (e: Exception) {
            abrirAfuera(Uri.parse(url))
        }
    }

    // --- Permisos ----------------------------------------------------------

    /**
     * Notificaciones (sin ellas no hay servicio en primer plano ni alarma de
     * pedidos) y dispositivos cercanos (la impresora integrada se publica como un
     * Bluetooth virtual).
     */
    private fun asegurarPermisos() {
        val faltan = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            faltan += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            faltan += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (faltan.isNotEmpty()) pedirPermisos.launch(faltan.toTypedArray())
    }

    /** Después de vincular: si no, Android duerme el servicio pasado un rato. */
    @SuppressLint("BatteryLife")
    fun pedirExencionDeBateria() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // Algunos POSNET traen esta pantalla capada; no es fatal.
        }
    }

    // --- Sin conexión --------------------------------------------------------

    private fun construirSinConexion(): View {
        val oscuro = ContextCompat.getColor(this, R.color.ucobot_oscuro)
        val verde = ContextCompat.getColor(this, R.color.ucobot_verde)
        val gris = ContextCompat.getColor(this, R.color.ucobot_gris)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(oscuro)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            isClickable = true

            addView(TextView(context).apply {
                text = "Sin conexión"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "UcoBot necesita internet para abrirse. Los pedidos que entren mientras tanto se imprimen cuando vuelva la conexión."
                setTextColor(gris)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(24))
            })
            addView(Button(context).apply {
                text = "Reintentar"
                setTextColor(oscuro)
                setBackgroundColor(verde)
                setOnClickListener {
                    web.reload()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(valor: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), resources.displayMetrics).toInt()
}
