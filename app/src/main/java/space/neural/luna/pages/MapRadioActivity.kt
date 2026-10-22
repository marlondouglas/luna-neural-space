package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
import org.osmdroid.views.overlay.Polyline
import space.neural.luna.carro.MediaLauncher
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.voice.HttpTokenProvider
import space.neural.luna.voice.VoiceRoomManager

/**
 * Tela MAPA + RÁDIO conforme o mockup.
 * Estrutura: topo (salas), esquerda (rota), centro (mapa), direita (online + chat),
 * baixo (PTT + status). Usuários reais via LiveKit; posições de terceiros exigem backend.
 */
class MapRadioActivity : AppCompatActivity(), LocationListener {
    private lateinit var ui: Ui
    private lateinit var map: MapView
    private var myMarker: Marker? = null
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val manager by lazy { VoiceRoomManager.get(this) }
    private val talkMutex = Mutex()
    private var talking = false

    private lateinit var statusMapa: TextView
    private lateinit var listaOnline: LinearLayout
    private lateinit var chatLista: LinearLayout
    private lateinit var btnPtt: Button
    private lateinit var radioStatus: TextView
    private var salaAtual = "Elétricos GV"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        ui = Ui(this)

        val raiz = ui.vbox().apply { setBackgroundColor(Cores.OBSIDIAN) }

        // Topo
        val topo = ui.hbox().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        topo.addView(ui.text("LUNA", 18f, Cores.BRANCO, bold = true))
        topo.addView(ui.text(" Rádio", 16f, Cores.BRANCO_70), ui.peso(0f, ui.dp(8)))
        topo.addView(chipSala("Elétricos GV", true), ui.peso(0f, ui.dp(6)))
        topo.addView(chipSala("Geral GV", false), ui.peso(0f, ui.dp(6)))
        topo.addView(chipSala("Aeroporto GV", false), ui.peso(0f, ui.dp(6)))
        topo.addView(ui.botao("+ Criar sala", Ui.Estilo.CONTORNO) { statusMapa.text = "Criar sala: em breve" }, ui.peso(0f, ui.dp(6)))
        topo.addView(ui.text("● AO VIVO", 13f, Cores.ESMERALDA), ui.peso(0f, ui.dp(8)))
        raiz.addView(topo)

        // Corpo em 3 colunas
        val corpo = ui.hbox().apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }

        // Esquerda: rota
        val esq = ui.vbox(ui.dp(8)).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(220), LinearLayout.LayoutParams.MATCH_PARENT) }
        esq.addView(ui.text("Rota no mapa", 15f, Cores.BRANCO, bold = true))
        val busca = EditText(this).apply {
            hint = "Digite um endereço..."
            setTextColor(Cores.BRANCO)
            setHintTextColor(Cores.BRANCO_70)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            background = ui.fundo(Cores.alpha(Cores.BRANCO, 0.08f), Cores.alpha(Cores.BRANCO, 0.2f), 8f)
        }
        esq.addView(busca, ui.mp(ui.dp(6)))
        val atalhos = ui.hbox()
        listOf("Casa", "Trabalho", "Favoritos").forEach { t ->
            atalhos.addView(ui.botao(t, Ui.Estilo.TEXTO) { busca.setText(t) }, ui.peso(1f))
        }
        esq.addView(atalhos)
        esq.addView(ui.text("Recentes", 13f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        listOf("Aeroporto de GV", "BR-381", "Shopping GV", "Eletroposto Zletric").forEach { r ->
            esq.addView(ui.text("• $r", 13f, Cores.BRANCO).apply {
                setOnClickListener { busca.setText(r) }
                setPadding(0, ui.dp(4), 0, ui.dp(4))
            })
        }
        esq.addView(ui.botao("Mostrar rota no mapa", Ui.Estilo.CHEIO_VIOLETA) {
            val dest = busca.text.toString().trim()
            if (dest.isEmpty()) { statusMapa.text = "Digite um endereço."; return@botao }
            val origem = myMarker?.position ?: GeoPoint(-18.851, -41.949)
            // Geocoding simples: usa Nominatim
            lifecycleScope.launch {
                val pt = runCatching { geocodificar(dest) }.getOrNull()
                if (pt == null) { statusMapa.text = "Endereço não encontrado."; return@launch }
                desenharRota(origem, pt)
            }
        }, ui.mp(ui.dp(8)))
        corpo.addView(esq)

        // Centro: mapa
        val centro = ui.vbox().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f) }
        statusMapa = ui.text("Localizando…", 12f, Cores.BRANCO_70)
        centro.addView(statusMapa, ui.mp(ui.dp(4)))
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(-18.851, -41.949))
        }
        centro.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        corpo.addView(centro)

        // Direita: online + chat
        val dir = ui.vbox(ui.dp(8)).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(240), LinearLayout.LayoutParams.MATCH_PARENT) }
        dir.addView(ui.text("NO AR AGORA", 14f, Cores.BRANCO, bold = true))
        listaOnline = ui.vbox()
        dir.addView(ScrollView(this).apply { addView(listaOnline) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dir.addView(ui.text("CHAT DA SALA", 14f, Cores.BRANCO, bold = true), ui.mp(ui.dp(6)))
        chatLista = ui.vbox()
        dir.addView(ScrollView(this).apply { addView(chatLista) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val msgInput = EditText(this).apply {
            hint = "Mensagem para $salaAtual..."
            setTextColor(Cores.BRANCO)
            setHintTextColor(Cores.BRANCO_70)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        val enviar = ui.botao("➤", Ui.Estilo.CHEIO_VIOLETA) {
            val t = msgInput.text.toString().trim()
            if (t.isNotEmpty()) {
                lifecycleScope.launch { runCatching { manager.sendText(t) } }
                msgInput.text.clear()
            }
        }
        val rowMsg = ui.hbox()
        rowMsg.addView(msgInput, ui.peso(1f, ui.dp(4)))
        rowMsg.addView(enviar)
        dir.addView(rowMsg, ui.mp(ui.dp(4)))
        corpo.addView(dir)

        raiz.addView(corpo)

        // Barra inferior
        val barra = ui.hbox().apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }
        radioStatus = ui.text("● Offline", 13f, Cores.BRANCO_70)
        barra.addView(radioStatus, ui.peso(0f, ui.dp(8)))
        btnPtt = Button(this).apply {
            text = "Segure para falar"
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
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        barra.addView(btnPtt, ui.peso(1f, ui.dp(8)))
        barra.addView(ui.botao("Reportar", Ui.Estilo.CONTORNO) {
            statusMapa.text = "Alerta enviado (integração real pendente)"
        }, ui.peso(0f, ui.dp(6)))
        barra.addView(ui.botao("Waze", Ui.Estilo.TEXTO) { MediaLauncher.abrirWaze(this) })
        raiz.addView(barra)

        setContentView(raiz)
        verificarPermissao()
        observarRadio()
        observarChat()
        atualizarOnline(emptyList())
    }

    private fun chipSala(nome: String, ativa: Boolean): Button =
        ui.botao(nome, if (ativa) Ui.Estilo.CHEIO_VIOLETA else Ui.Estilo.CONTORNO) {
            salaAtual = nome
            statusMapa.text = "Sala: $nome"
        }

    private fun atualizarOnline(nomes: List<String>) {
        listaOnline.removeAllViews()
        if (nomes.isEmpty()) {
            listaOnline.addView(ui.text("Nenhum motorista na sala.\nEntre para aparecer.", 12f, Cores.BRANCO_70))
            return
        }
        nomes.forEach { n ->
            listaOnline.addView(ui.text("● $n", 13f, Cores.BRANCO), ui.mp(ui.dp(3)))
        }
    }

    private fun verificarPermissao() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            statusMapa.text = "Permissão de localização necessária."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3001)
            return
        }
        iniciarGps()
    }

    private fun iniciarGps() {
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!gps) { statusMapa.text = "GPS desligado."; return }
        statusMapa.text = "Localizando…"
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, this) }
    }

    private fun atualizarPos(loc: Location) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        if (myMarker == null) myMarker = Marker(map).also { map.overlays.add(it) }
        myMarker?.position = p
        myMarker?.title = "Você"
        map.controller.animateTo(p)
        map.invalidate()
        statusMapa.text = "Você: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
    }

    private fun entrarSala() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3002)
            radioStatus.text = "Permissão de microfone necessária."
            return
        }
        lifecycleScope.launch {
            runCatching {
                manager.join(salaAtual.lowercase().replace(" ", "-"), "motorista", HttpTokenProvider())
            }.onFailure { radioStatus.text = "Erro: ${it.message}" }
        }
    }

    private fun observarRadio() {
        lifecycleScope.launch {
            manager.state.collect { s ->
                when (s) {
                    is VoiceRoomManager.State.Disconnected -> {
                        radioStatus.text = "● Offline"
                        btnPtt.isEnabled = false
                    }
                    is VoiceRoomManager.State.Connecting -> radioStatus.text = "Conectando…"
                    is VoiceRoomManager.State.Connected -> {
                        radioStatus.text = "● Online"
                        btnPtt.isEnabled = true
                    }
                    is VoiceRoomManager.State.Error -> radioStatus.text = s.message
                }
            }
        }
    }

    private fun observarChat() {
        lifecycleScope.launch {
            manager.messages.collect { msg ->
                val linha = ui.text("${msg.from ?: "Você"}: ${msg.text}", 12f, Cores.BRANCO)
                chatLista.addView(linha, ui.mp(ui.dp(2)))
            }
        }
    }

    override fun onLocationChanged(location: Location) = atualizarPos(location)
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 3001 && r.any { it == PackageManager.PERMISSION_GRANTED }) iniciarGps()
        if (c == 3002 && r.any { it == PackageManager.PERMISSION_GRANTED }) entrarSala()
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() {
        runCatching { lm.removeUpdates(this) }
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        map.onDetach()
        super.onDestroy()
    }

    private fun desenharRota(de: GeoPoint, para: GeoPoint) {
        lifecycleScope.launch {
            val pontos = runCatching { buscarRotaOsrm(de, para) }.getOrNull()
            if (pontos.isNullOrEmpty()) {
                statusMapa.text = "Rota não encontrada. Verifique o endereço."
                return@launch
            }
            map.overlays.removeAll { it is Polyline }
            val linha = Polyline().apply {
                setPoints(pontos)
                outlinePaint.color = Cores.CIANO
                outlinePaint.strokeWidth = 8f
            }
            map.overlays.add(linha)
            map.invalidate()
            val km = pontos.zipWithNext().sumOf { (a, b) ->
                val r = FloatArray(1)
                android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
                r[0].toDouble()
            } / 1000.0
            statusMapa.text = "Rota: ${"%.1f".format(km)} km"
        }
    }

    private suspend fun buscarRotaOsrm(de: GeoPoint, para: GeoPoint): List<GeoPoint>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "https://router.project-osrm.org/route/v1/driving/${de.longitude},${de.latitude};${para.longitude},${para.latitude}?overview=full&geometries=geojson"
            val json = java.net.URL(url).readText()
            val arr = org.json.JSONObject(json).optJSONArray("routes") ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val coords = arr.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            (0 until coords.length()).map { i ->
                val c = coords.getJSONArray(i)
                GeoPoint(c.getDouble(1), c.getDouble(0))
            }
        }


    private suspend fun geocodificar(endereco: String): GeoPoint? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val q = java.net.URLEncoder.encode("$endereco, Governador Valadares", "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1"
            val arr = org.json.JSONArray(java.net.URL(url).readText())
            if (arr.length() == 0) return@withContext null
            val o = arr.getJSONObject(0)
            GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
        }

}
