package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import space.neural.luna.carro.MediaLauncher
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples

/**
 * Página MAPA + RÁDIO (baseada no mockup do PDF).
 * Faixa SOS no topo, mapa com motoristas online, card lateral e botões de ação.
 * Separada do dashboard 0.1.7.
 */
class MapRadioActivity : AppCompatActivity(), LocationListener {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var map: MapView
    private var myMarker: Marker? = null
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Mapa e Rádio")

        // Faixa SOS
        val sos = ui.text("SOS  ·  Toque para pedir ajuda", 14f, Cores.BRANCO).apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.fundo(Cores.alpha(Cores.CORAL, 0.25f), Cores.CORAL, 8f)
            setOnClickListener { status.text = "SOS enviado (simulação — integração real pendente)" }
        }
        c.addView(sos, ui.mp(ui.dp(4)))

        status = ui.text("Buscando motoristas online…", 13f, Cores.BRANCO_70)
        c.addView(status, ui.mp(ui.dp(4)))

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(-18.851, -41.949))
        }
        c.addView(map, LinearLayout.LayoutParams(-1, ui.dp(360)).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(6) })

        // Card lateral de exemplo (dados reais viriam de Firebase/LiveKit)
        val card = ui.card(acento = Cores.VIOLETA)
        card.addView(ui.text("Motoristas online: 0", 15f, Cores.BRANCO))
        card.addView(ui.text("Nenhum parceiro visível no momento.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(4)))
        c.addView(card, ui.mp(ui.dp(6)))

        val row = ui.hbox()
        row.addView(Button(this).apply { text = "Abrir Waze"; setOnClickListener { MediaLauncher.abrirWaze(this@MapRadioActivity) } }, ui.peso(1f, ui.dp(4)))
        row.addView(Button(this).apply { text = "Falar no rádio"; setOnClickListener { startActivity(android.content.Intent(this@MapRadioActivity, RadioActivity::class.java)) } }, ui.peso(1f, ui.dp(4)))
        c.addView(row, ui.mp(ui.dp(6)))

        setContentView(raiz)
        verificarPermissao()
    }

    private fun verificarPermissao() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            status.text = "Permissão de localização necessária para ver sua posição."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2001)
            return
        }
        iniciar()
    }

    private fun iniciar() {
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!gps) { status.text = "GPS desligado."; return }
        status.text = "Localizando…"
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, this) }
    }

    private fun atualizar(loc: Location) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        if (myMarker == null) myMarker = Marker(map).also { map.overlays.add(it) }
        myMarker?.position = p
        myMarker?.title = "Você"
        map.controller.animateTo(p)
        map.invalidate()
        status.text = "Você: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
    }

    override fun onLocationChanged(location: Location) = atualizar(location)
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 2001 && r.any { it == PackageManager.PERMISSION_GRANTED }) iniciar()
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { runCatching { lm.removeUpdates(this) }; map.onPause(); super.onPause() }
    override fun onDestroy() { runCatching { lm.removeUpdates(this) }; map.onDetach(); super.onDestroy() }
}
