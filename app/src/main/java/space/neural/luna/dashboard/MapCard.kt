package space.neural.luna.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import space.neural.luna.carro.MediaLauncher

/**
 * Card de mapa/GPS simples.
 * Mostra status da localização e botão para abrir Waze.
 * Não lê dados internos do Waze. Avisa se não houver GPS.
 */
class MapCard(private val ui: Ui, private val activity: android.app.Activity) {

    val view: View
    private val status: TextView
    private val btnWaze: Button
    private val locationManager = activity.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager

    init {
        val root = ui.vbox().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        root.addView(ui.text("Mapa / GPS", 16f, Cores.BRANCO).apply { setPadding(0, 0, 0, ui.dp(8)) })

        status = ui.text("Verificando localização…", 14f, Cores.BRANCO_70)
        root.addView(status)

        btnWaze = Button(activity).apply {
            text = "Abrir Waze"
            setOnClickListener { MediaLauncher.abrirWaze(activity) }
        }
        root.addView(btnWaze, ui.mp(ui.dp(8)))

        view = root
        atualizarStatus()
    }

    fun atualizarStatus() {
        val fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            status.text = "Permissão de localização necessária. Toque para solicitar."
            status.setOnClickListener { activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001) }
            return
        }
        val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                      locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            status.text = "GPS desligado. Ative a localização no aparelho."
            return
        }
        status.text = "Localização disponível. Toque em Abrir Waze para navegar."
        // Opcional: tentar obter última localização conhecida (sem listener contínuo pesado)
        runCatching {
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) {
                status.text = "Posição: ${String.format("%.4f", last.latitude)}, ${String.format("%.4f", last.longitude)}"
            }
        }
    }
}
