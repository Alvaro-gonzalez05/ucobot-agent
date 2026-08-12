package com.codea.ucobot.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codea.ucobot.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La única pantalla de la app.
 *
 * Se abre dos veces en la vida del equipo: cuando se instala, para pegar el
 * código de vinculación, y cuando algo no imprime, para ver por qué. El resto del
 * tiempo el agente vive en la notificación fija y nadie entra acá.
 *
 * Por eso no tiene menús ni configuración: lo que se puede tocar se toca desde
 * UcoBot, en el panel del dueño, que es donde ya está mirando.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vista: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pedirPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { pintar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)

        vista = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vista.root)

        vista.version.text = "v${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL}"

        vista.btnVincular.setOnClickListener { vincular() }
        vista.btnProbar.setOnClickListener { probarImpresion() }
        vista.btnDesvincular.setOnClickListener { confirmarDesvinculacion() }
        vista.btnActualizar.setOnClickListener { actualizar() }

        asegurarPermisos()
        if (Config.isPaired) AgentService.iniciar(this)

        // Mientras la pantalla está abierta se refresca sola: es donde alguien
        // está mirando si el equipo se conectó.
        scope.launch {
            while (true) {
                pintar()
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- Permisos ----------------------------------------------------------

    /**
     * Tres permisos y ninguno es opcional:
     *  - notificaciones: sin ellas Android no deja tener el servicio en primer
     *    plano, que es lo único que evita que maten al agente
     *  - dispositivos cercanos: así se llega a la impresora integrada, que el
     *    sistema publica como un Bluetooth virtual
     *  - batería sin optimizar: si no, el sistema lo duerme igual pasado un rato
     */
    private fun asegurarPermisos() {
        val faltan = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            faltan += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            faltan += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (faltan.isNotEmpty()) pedirPermisos.launch(faltan.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun pedirExencionDeBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // Algunos equipos de POS traen esta pantalla capada; no es fatal.
        }
    }

    // --- Acciones ----------------------------------------------------------

    private fun vincular() {
        val codigo = vista.campoCodigo.text.toString().trim()
        if (codigo.isBlank()) {
            mensaje("Escribí el código que generaste en UcoBot", error = true)
            return
        }

        vista.btnVincular.isEnabled = false
        vista.btnVincular.text = "Vinculando..."

        scope.launch {
            try {
                val nombre = withContext(Dispatchers.IO) { Api.pair(codigo) }
                mensaje("Listo: este equipo quedó vinculado como \"$nombre\".", error = false)
                AgentService.iniciar(this@MainActivity)
                pedirExencionDeBateria()
            } catch (e: Api.ApiException) {
                mensaje(e.message ?: "No se pudo vincular", error = true)
            } catch (e: Exception) {
                mensaje("No se pudo conectar con UcoBot. ¿Hay internet?", error = true)
            } finally {
                vista.btnVincular.isEnabled = true
                vista.btnVincular.text = "Vincular"
                pintar()
            }
        }
    }

    private fun probarImpresion() {
        vista.btnProbar.isEnabled = false
        vista.btnProbar.text = "Imprimiendo..."

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Printer.print(Printer.ticketDePrueba(Config.ticketWidth))
                }
                mensaje("Salió el ticket de prueba.", error = false)
            } catch (e: Exception) {
                mensaje(e.message ?: "No se pudo imprimir", error = true)
            } finally {
                vista.btnProbar.isEnabled = true
                vista.btnProbar.text = "Imprimir prueba"
            }
        }
    }

    /**
     * Instala la versión que el servicio ya dejó bajada.
     *
     * El permiso de "instalar apps desconocidas" no se resuelve con un diálogo:
     * hay que mandar a la persona a Ajustes. Si no se explica acá, el sistema
     * rebota sin decir nada y parece que el botón no anda.
     */
    private fun actualizar() {
        if (Updater.necesitaPermisoDeInstalacion(this)) {
            mensaje(
                "Android necesita tu permiso para instalar la actualización. " +
                    "Activá \"Permitir de esta fuente\" y volvé.",
                error = false
            )
            try {
                startActivity(Updater.intentDePermiso(this))
            } catch (e: Exception) {
                mensaje("No se pudo abrir Ajustes: activá 'instalar apps desconocidas' a mano", true)
            }
            return
        }

        val intent = Updater.intentDeInstalacion(this)
        if (intent == null) {
            mensaje("La actualización todavía se está descargando", error = false)
            return
        }
        startActivity(intent)
    }

    private fun confirmarDesvinculacion() {
        AlertDialog.Builder(this)
            .setTitle("Desvincular este equipo")
            .setMessage("Va a dejar de imprimir al instante. Para volver a usarlo hay que generar un código nuevo.")
            .setPositiveButton("Desvincular") { _, _ ->
                Config.unpair()
                AgentService.detener(this)
                mensaje("Equipo desvinculado.", error = false)
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- Pintado -----------------------------------------------------------

    private fun pintar() {
        val vinculado = Config.isPaired

        vista.bloqueEstado.visibility = if (vinculado) View.VISIBLE else View.GONE
        vista.bloquePareo.visibility = if (vinculado) View.GONE else View.VISIBLE

        if (!vinculado) return

        val nueva = Updater.pendiente
        vista.btnActualizar.visibility = if (nueva != null) View.VISIBLE else View.GONE
        if (nueva != null) vista.btnActualizar.text = "Actualizar a la versión $nueva"

        vista.estado.text = when {
            !AgentService.corriendo -> "Servicio detenido"
            AgentService.timbreConectado -> "● Conectado y listo"
            else -> "○ Reconectando..."
        }

        val impresora = if (Printer.disponible()) "detectada" else "NO detectada"
        val lineas = mutableListOf(
            "Equipo: ${Config.name ?: "-"}",
            "Impresora integrada: $impresora",
            "Papel: ${Config.ticketWidth} mm",
            "Tickets impresos: ${AgentService.trabajosHechos}",
        )
        if (AgentService.trabajosFallidos > 0) {
            lineas += "Con error: ${AgentService.trabajosFallidos}"
        }
        AgentService.ultimoError?.let { lineas += "Último error: $it" }

        vista.detalle.text = lineas.joinToString("\n")
    }

    private fun mensaje(texto: String, error: Boolean) {
        vista.mensaje.text = texto
        vista.mensaje.setTextColor(if (error) 0xFFFCA5A5.toInt() else 0xFF86EFAC.toInt())
        vista.mensaje.visibility = View.VISIBLE
    }
}
