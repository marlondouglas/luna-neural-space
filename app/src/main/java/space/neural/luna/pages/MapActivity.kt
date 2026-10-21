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
 * Página MAPA (separada do dashboard).
 * Mapa OSM (osmdroid) com posição atual, zoom/pan e botão Waze.
 */
class MapActivity : AppCompatActivity(), LocationListener {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var map: MapView
    private var marker: Marker? = null
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Mapa")

        status = ui.text("Verificando localização…", 14f, Cores.BRANCO)
        c.addView(status, ui.mp(ui.dp(6)))

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(-18.851, -41.949)) // Governador Valadares (fallback)
        }
        c.addView(map, LinearLayout.LayoutParams(-1, ui.dp(420)).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })

        val btn = Button(this).apply {
            text = "Abrir Waze"
            setOnClickListener { MediaLauncher.abrirWaze(this@MapActivity) }
        }
        c.addView(btn, ui.mp(ui.dp(8)))

        val attr = ui.text("Mapa © OpenStreetMap contributors (tiles OSM)", 12f, Cores.BRANCO_70)
        c.addView(attr, ui.mp(ui.dp(4)))

        setContentView(raiz)
        verificarPermissao()
    }

    private fun verificarPermissao() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            status.text = "Permissão de localização necessária."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            return
        }
        iniciarLocalizacao()
    }

    private fun iniciarLocalizacao() {
        val gps = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (!gps && !net) {
            status.text = "GPS desligado. Ative a localização no aparelho."
            return
        }
        status.text = "Buscando posição…"
        runCatching {
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) atualizar(last, "Última posição conhecida")
        }
        if (gps) runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this) }
        if (net) runCatching { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this) }
    }

    private fun atualizar(loc: Location, origem: String) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        if (marker == null) {
            marker = Marker(map).also { map.overlays.add(it) }
        }
        marker?.position = p
        marker?.title = "Você"
        map.controller.animateTo(p)
        map.invalidate()
        status.text = "$origem: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
    }

    override fun onLocationChanged(location: Location) = atualizar(location, "Posição atual")
    override fun onProviderEnabled(provider: String) { status.text = "Provedor $provider ativado." }
    override fun onProviderDisabled(provider: String) { status.text = "Provedor $provider desativado." }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) iniciarLocalizacao()
        else status.text = "Localização negada. O mapa continua navegável sem sua posição."
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() {
        runCatching { locationManager.removeUpdates(this) }
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        map.onDetach()
        super.onDestroy()
    }
}
