package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import space.neural.luna.carro.MediaLauncher
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples

/**
 * Página MAPA (separada do dashboard).
 * Localização atual + botão Waze. Não altera a tela inicial.
 */
class MapActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Mapa")

        status = ui.text("Verificando localização…", 16f, Cores.BRANCO)
        c.addView(status, ui.mp(ui.dp(8)))

        val btn = Button(this).apply {
            text = "Abrir Waze"
            setOnClickListener { MediaLauncher.abrirWaze(this@MapActivity) }
        }
        c.addView(btn, ui.mp(ui.dp(12)))

        val info = ui.text(
            "Mapa interativo completo (OpenStreetMap / Google) será adicionado quando a integração de tiles estiver validada. " +
            "Por enquanto: GPS + Waze.",
            14f, Cores.BRANCO_70
        )
        c.addView(info, ui.mp(ui.dp(8)))

        setContentView(raiz)
        atualizar()
    }

    private fun atualizar() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            status.text = "Permissão de localização necessária."
            status.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001) }
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            status.text = "GPS desligado. Ative a localização."
            return
        }
        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            status.text = if (last != null)
                "Posição: ${"%.5f".format(last.latitude)}, ${"%.5f".format(last.longitude)}"
            else "Localização disponível. Toque em Abrir Waze."
        }.onFailure { status.text = "Não foi possível ler a posição." }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) atualizar()
    }
}
