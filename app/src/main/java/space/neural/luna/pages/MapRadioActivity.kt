package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import space.neural.luna.carro.MediaLauncher
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.voice.HttpTokenProvider
import space.neural.luna.voice.VoiceRoomManager

/**
 * Tela única de MAPA + RÁDIO (conforme PDF).
 * Mapa OSM com posição atual e controles de rádio LiveKit na mesma página.
 */
class MapRadioActivity : AppCompatActivity(), LocationListener {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var map: MapView
    private var myMarker: Marker? = null
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val manager by lazy { VoiceRoomManager.get(this) }
    private val talkMutex = Mutex()
    private var talking = false
    private lateinit var btnPtt: Button
    private lateinit var radioStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Mapa e Rádio")

        // SOS
        val sos = ui.text("SOS  ·  Toque para pedir ajuda", 14f, Cores.BRANCO).apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.fundo(Cores.alpha(Cores.CORAL, 0.25f), Cores.CORAL, 8f)
            setOnClickListener { status.text = "SOS enviado (integração real pendente)" }
        }
        c.addView(sos, ui.mp(ui.dp(4)))

        status = ui.text("Buscando posição…", 13f, Cores.BRANCO_70)
        c.addView(status, ui.mp(ui.dp(4)))

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(-18.851, -41.949))
        }
        c.addView(map, LinearLayout.LayoutParams(-1, ui.dp(320)).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(6) })

        // Controles de rádio
        radioStatus = ui.text("Rádio: desconectado", 13f, Cores.BRANCO_70)
        c.addView(radioStatus, ui.mp(ui.dp(4)))

        val row = ui.hbox()
        row.addView(Button(this).apply { text = "Entrar no rádio"; setOnClickListener { entrarRadio() } }, ui.peso(1f, ui.dp(4)))
        btnPtt = Button(this).apply {
            text = "Apertar para falar"
            isEnabled = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.startTalking() }.onSuccess { talking = true }
                                    .onFailure { radioStatus.text = "Erro mic: ${it.message}" }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.stopTalking() }.onSuccess { talking = false }
                                    .onFailure { radioStatus.text = "Erro ao soltar: ${it.message}" }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        row.addView(btnPtt, ui.peso(1f, ui.dp(4)))
        c.addView(row, ui.mp(ui.dp(4)))

        val row2 = ui.hbox()
        row2.addView(Button(this).apply { text = "Sair do rádio"; setOnClickListener { sairRadio() } }, ui.peso(1f, ui.dp(4)))
        row2.addView(Button(this).apply { text = "Abrir Waze"; setOnClickListener { MediaLauncher.abrirWaze(this@MapRadioActivity) } }, ui.peso(1f, ui.dp(4)))
        c.addView(row2, ui.mp(ui.dp(4)))

        setContentView(raiz)
        verificarPermissao()
        observarRadio()
    }

    private fun verificarPermissao() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            status.text = "Permissão de localização necessária."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3001)
            return
        }
        iniciarGps()
    }

    private fun iniciarGps() {
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

    private fun entrarRadio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3002)
            radioStatus.text = "Permissão de microfone necessária."
            return
        }
        lifecycleScope.launch {
            runCatching {
                manager.join("rota-gv", "motorista-${System.currentTimeMillis() % 10000}", HttpTokenProvider())
            }.onFailure { radioStatus.text = "Erro ao entrar: ${it.message}" }
        }
    }

    private fun sairRadio() {
        lifecycleScope.launch {
            talkMutex.withLock {
                if (talking) runCatching { manager.stopTalking() }
                talking = false
            }
            runCatching { manager.leave() }
        }
    }

    private fun observarRadio() {
        lifecycleScope.launch {
            manager.state.collect { s ->
                when (s) {
                    is VoiceRoomManager.State.Disconnected -> {
                        radioStatus.text = "Rádio: desconectado"
                        btnPtt.isEnabled = false
                    }
                    is VoiceRoomManager.State.Connecting -> radioStatus.text = "Rádio: conectando…"
                    is VoiceRoomManager.State.Connected -> {
                        radioStatus.text = "Rádio: conectado"
                        btnPtt.isEnabled = true
                    }
                    is VoiceRoomManager.State.Error -> radioStatus.text = "Rádio: ${s.message}"
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) = atualizar(location)
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 3001 && r.any { it == PackageManager.PERMISSION_GRANTED }) iniciarGps()
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() {
        lifecycleScope.launch { sairRadio() }
        runCatching { lm.removeUpdates(this) }
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        map.onDetach()
        super.onDestroy()
    }
}
