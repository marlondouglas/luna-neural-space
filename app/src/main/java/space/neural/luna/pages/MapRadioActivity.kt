package space.neural.luna.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import space.neural.luna.BuildConfig
import space.neural.luna.automotive.AutomotiveProfile
import space.neural.luna.R
import space.neural.luna.collaboration.*
import space.neural.luna.config.ConfiguracoesActivity
import space.neural.luna.conta.Conta
import space.neural.luna.dashboard.*
import space.neural.luna.navigation.*
import space.neural.luna.partners.*
import space.neural.luna.sync.Nuvem
import java.util.UUID

/** The map remains primary; side panels become dismissible sheets on narrow displays. */
class MapRadioActivity : AppCompatActivity(), LocationListener {
    private lateinit var ui: Ui
    private lateinit var map: MapView
    private lateinit var controls: DrivingControls
    private lateinit var roomPanel: RoomPanel
    private lateinit var navigation: NavigationRepository
    private lateinit var routePanel: View
    private lateinit var search: AutoCompleteTextView
    private var autocompleteJob: Job? = null
    private var autocompleteResults: List<SavedDestination> = emptyList()
    private var suppressAutocomplete = false
    private lateinit var info: TextView
    private lateinit var guidance: TextView
    private lateinit var gps: TextView
    private lateinit var recentRows: LinearLayout
    private lateinit var layerLabel: TextView
    private var fix: Location? = null
    private var fixReceivedWithFinePermission = false
    private var minimumAuthorizedFixTime = 0L
    private var ownMarker: Marker? = null
    private var target: SavedDestination? = null
    private var targetEphemeral = false
    private var targetSourceKey: String? = null
    private val activeSosEvents = mutableMapOf<String, CommunityEvent>()
    private val activeDriverPositions = mutableMapOf<String, DriverPresence>()
    private var sosDialog: AlertDialog? = null
    private var sosDialogKey: String? = null
    private var route: Route? = null
    private var navigating = false
    private var routeJob: Job? = null
    private var observers: Job? = null
    private var gpsFreshnessJob: Job? = null
    private var lastReroute = 0L
    private var lastPresenceFix = 0L
    private var share = false
    private var pendingFavorite: String? = null
    private var lastSosId: String? = null
    private var manualOrigin: GeoPoint? = null
    private var firstFix = true
    private var followVehicle = true
    private var headingUp = false
    private var pendingAction: String? = null
    private var navigationSpeech: NavigationSpeechController? = null
    private var voiceEnabled = true
    private val preferences by lazy { getSharedPreferences("driving", 0) }
    private val automotiveProfile by lazy { AutomotiveProfile.current(this) }
    private val locations by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val community by lazy { runCatching { if (Nuvem.firebaseOk(this)) CommunityRepository() else null }.getOrNull() }
    private var presence: PresenceSession? = null
    private enum class Layer(val label: String) { DRIVERS("Motoristas"), CHARGING("Eletropostos"), PARTNERS("Parceiros"), ALERTS("Alertas"), TRAFFIC("Trânsito reportado"), ROUTE("Rota ativa") }
    private val visible = Layer.entries.associateWith { true }.toMutableMap()
    private val overlays = Layer.entries.associateWith { mutableListOf<Overlay>() }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startGps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
        ui = Ui(this); navigation = NavigationRepository(this)
        share = preferences.getBoolean("share_location", false)
        voiceEnabled = preferences.getBoolean("navigation_voice", true)
        headingUp = preferences.getBoolean("heading_up", false)
        pendingAction = intent.getStringExtra("ACTION")
        target = savedInstanceState?.getString("destination_name")?.let { SavedDestination("restored", it, savedInstanceState.getDouble("destination_lat"), savedInstanceState.getDouble("destination_lon")) }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            setTilesScaledToDpi(true); controller.setZoom(15.0); controller.setCenter(GeoPoint(-18.851, -41.949))
            minZoomLevel = 3.0; maxZoomLevel = 20.0
            overlays.add(org.osmdroid.views.overlay.CopyrightOverlay(this@MapRadioActivity))
        }
        map.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) followVehicle = false
            false
        }
        controls = DrivingControls(this, "mapa", { room -> roomPanel.selectRoom(room); subscribeRoom() }, ::navigate, ::report, ::sos) { publishPresence() }
        roomPanel = RoomPanel(this, onDrivers = { drivers -> renderDrivers(drivers) })
        guidance = ui.text("Selecione um destino", 16f, Cores.BRANCO, true).apply {
            background = RotaDesign.panel(ui, Cores.CIANO); setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)); maxLines = 4
        }
        routePanel = buildRoutePanel()
        val root = ui.vbox().apply { setBackgroundColor(Cores.OBSIDIAN) }
        root.addView(controls.header, ui.mp())
        root.addView(buildMapBody(), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(controls.audioBar, ui.mp(ui.dp(4)))
        root.addView(controls.dock, ui.mp(ui.dp(4)))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        renderPartners(); roomPanel.selectRoom(controls.room)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { controls.manager.talking.collect { if (it) navigationSpeech?.stop() } }
            launch { controls.manager.activeSpeakers.collect { if (it.any { speaker -> !speaker.local }) navigationSpeech?.stop() } }
        } }
        val incomingName = intent.getStringExtra("DESTINO")
        if (intent.hasExtra("LATITUDE") && intent.hasExtra("LONGITUDE")) {
            selectDestination(SavedDestination("intent", incomingName ?: "Destino compartilhado", intent.getDoubleExtra("LATITUDE", 0.0), intent.getDoubleExtra("LONGITUDE", 0.0)))
        } else if (!incomingName.isNullOrBlank()) { search.setText(incomingName); searchAddress() }
        else target?.let { selectDestination(it) }
    }

    private fun buildMapBody(): View {
        val frame = FrameLayout(this)
        frame.addView(map, FrameLayout.LayoutParams(-1, -1))
        val wide = automotiveProfile.mapSidePanels
        val sideWidth = (resources.configuration.screenWidthDp * .235f).toInt().coerceIn(208, 330)
        val rightWidth = (resources.configuration.screenWidthDp * .255f).toInt().coerceIn(230, 360)
        if (wide) {
            frame.addView(routePanel, FrameLayout.LayoutParams(ui.dp(sideWidth), -1, Gravity.START).apply { setMargins(ui.dp(12), ui.dp(12), 0, ui.dp(12)) })
            frame.addView(roomPanel.view, FrameLayout.LayoutParams(ui.dp(rightWidth), -1, Gravity.END).apply { setMargins(0, ui.dp(12), ui.dp(12), ui.dp(12)) })
        } else {
            val tools = ui.hbox()
            tools.addView(ui.botao("Buscar / rota", Ui.Estilo.CHEIO_VIOLETA) { sheet("Rota no mapa", routePanel) }, ui.wc())
            tools.addView(ui.botao("Sala / chat", Ui.Estilo.CONTORNO) { sheet("Participantes e chat", roomPanel.view) }.apply { background = RotaDesign.panel(ui) }, ui.wc(ui.dp(8)))
            frame.addView(RotaDesign.scrollRow(ui, tools), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(10), ui.dp(10), ui.dp(60), 0) })
        }
        val tools = ui.vbox()
        val touchSize = automotiveProfile.minimumTouchDp
        tools.addView(RotaDesign.roundIcon(ui, R.drawable.ic_add, "Aumentar mapa") { map.controller.zoomIn() }, ui.mp(ui.dp(5)))
        tools.addView(ui.botao("−", Ui.Estilo.CONTORNO) { map.controller.zoomOut() }.apply { textSize = 27f; background = RotaDesign.panel(ui) }, LinearLayout.LayoutParams(ui.dp(touchSize), ui.dp(touchSize)).apply { topMargin = ui.dp(5) })
        tools.addView(RotaDesign.roundIcon(ui, R.drawable.ic_location, "Minha localização") { if (hasLocation()) { startGps(); fix?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude)) } } else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, ui.mp(ui.dp(5)))
        tools.addView(RotaDesign.roundIcon(ui, R.drawable.ic_layers, "Camadas do mapa") { layersDialog() }, ui.mp(ui.dp(5)))
        tools.addView(RotaDesign.roundIcon(ui, R.drawable.ic_car, "Seguir veículo e orientação") { followSettings() }, ui.mp(ui.dp(5)))
        (0 until tools.childCount).forEach { index -> tools.getChildAt(index).apply { minimumWidth = ui.dp(touchSize); minimumHeight = ui.dp(touchSize); isFocusable = true } }
        if (resources.configuration.screenHeightDp < 500) {
            val items = (0 until tools.childCount).map { tools.getChildAt(it) }
            tools.removeAllViews()
            items.chunked(2).forEach { pair -> tools.addView(ui.hbox().apply {
                pair.forEach { addView(it, LinearLayout.LayoutParams(ui.dp(touchSize), ui.dp(touchSize)).apply { setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) }) }
            }) }
        }
        frame.addView(tools, FrameLayout.LayoutParams(ui.dp(if (resources.configuration.screenHeightDp < 500) (touchSize + 4) * 2 else touchSize), -2, Gravity.END or Gravity.TOP).apply { topMargin = ui.dp(if (wide) 14 else 70); rightMargin = ui.dp(if (wide) rightWidth + 24 else 10) })
        val summary = ui.vbox()
        layerLabel = RotaDesign.badge(ui, "Motoristas 0  ·  Eletropostos ${PartnerCatalog.partners.count { it.category == PartnerCategory.CHARGING }}", Cores.ESMERALDA)
        layerLabel.setOnClickListener { layersDialog() }
        gps = RotaDesign.badge(ui, "◎ GPS · toque para localizar", Cores.CIANO).apply { setOnClickListener { if (hasLocation()) startGps() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
        if (wide) summary.addView(layerLabel, ui.mp(ui.dp(2)))
        summary.addView(gps, ui.mp(ui.dp(5)))
        summary.addView(guidance, ui.mp(ui.dp(6)))
        frame.addView(summary, FrameLayout.LayoutParams(if (wide) ui.dp((resources.configuration.screenWidthDp - sideWidth - rightWidth - 100).coerceAtLeast(180)) else -1, -2, Gravity.BOTTOM or if (wide) Gravity.CENTER_HORIZONTAL else Gravity.START).apply {
            bottomMargin = ui.dp(18); leftMargin = ui.dp(if (wide) 0 else 10); rightMargin = ui.dp(if (wide) 0 else 10)
        })
        return frame
    }

    private fun buildRoutePanel(): View {
        val box = RotaDesign.panelBox(ui)
        box.addView(ui.hbox().apply { addView(ui.icone(R.drawable.ic_location, Cores.MAGENTA)); addView(ui.text("Rota no mapa", 17f, Cores.BRANCO, true), ui.wc(ui.dp(8))) })
        search = AutoCompleteTextView(this).apply {
            hint = "Digite um endereço…"; textSize = 15f; threshold = 1
            setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = RotaDesign.panel(ui)
            setOnItemClickListener { parent, _, position, _ ->
                val label = parent.getItemAtPosition(position).toString()
                val destination = autocompleteResults.firstOrNull { it.name == label }
                    ?: (navigation.favorites() + navigation.recent()).firstOrNull { it.name == label }
                destination?.let { selectDestination(it); calculateRoute() }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString().orEmpty().trim()
                    autocompleteResults = navigation.suggestions(query)
                    setAdapter(ArrayAdapter(this@MapRadioActivity, android.R.layout.simple_dropdown_item_1line, autocompleteResults.map { it.name }))
                    autocompleteJob?.cancel()
                    if (!suppressAutocomplete && query.length >= 3) autocompleteJob = lifecycleScope.launch {
                        delay(700)
                        val phrase = if (query.contains("Valadares", true)) query else "$query, Governador Valadares, MG"
                        val remote = runCatching { navigation.search(phrase) }.getOrDefault(emptyList())
                        if (search.text.toString().trim() == query && remote.isNotEmpty()) {
                            autocompleteResults = (remote + navigation.suggestions(query)).distinctBy { it.name }.take(8)
                            setAdapter(ArrayAdapter(this@MapRadioActivity, android.R.layout.simple_dropdown_item_1line, autocompleteResults.map { it.name }))
                            if (hasFocus()) showDropDown()
                        }
                    }
                }
            })
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { searchAddress(); true } else false }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val searchRow = ui.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(ui.botaoIcone(R.drawable.ic_send, "Buscar endereço e traçar rota", Cores.CIANO) { searchAddress() }, ui.wc(ui.dp(6)))
        box.addView(searchRow, ui.mp(ui.dp(12)))
        val shortcuts = ui.hbox()
        listOf("home" to "Casa", "work" to "Trabalho", "favorites" to "Favoritos").forEach { (id, label) ->
            shortcuts.addView(ui.botao(label, Ui.Estilo.CONTORNO) { openFavorite(id) }.apply { textSize = 12f; setPadding(ui.dp(7), 0, ui.dp(7), 0) }, ui.peso(1f, ui.dp(2)))
        }
        box.addView(shortcuts, ui.mp(ui.dp(8)))
        val list = ui.vbox()
        list.addView(ui.text("Recentes e favoritos", 15f, Cores.BRANCO_70, true), ui.mp(ui.dp(14)))
        recentRows = ui.vbox(); list.addView(recentRows, ui.mp(ui.dp(6)))
        list.addView(ui.text("Eletropostos em GV", 15f, Cores.BRANCO_70, true), ui.mp(ui.dp(12)))
        PartnerCatalog.partners.filter { it.category == PartnerCategory.CHARGING }.forEach { partner ->
            list.addView(ui.hbox().apply {
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.fundo(Cores.ELEVADA, raio = 13f)
                addView(ui.icone(R.drawable.ic_bolt, Cores.CIANO))
                addView(ui.vbox().apply { addView(ui.text(partner.name, 14f, Cores.BRANCO, true)); addView(ui.text(partner.address.orEmpty(), 12f, Cores.BRANCO_70).apply { maxLines = 2 }); addView(ui.text("Disponibilidade não confirmada", 11f, Cores.BRANCO_70)) }, ui.peso(1f, ui.dp(8)))
                setOnClickListener { partnerDestination(partner) }
            }, ui.mp(ui.dp(5)))
        }
        val sharing = Switch(this).apply {
            text = "Compartilhar minha posição"; textSize = 13f; setTextColor(Cores.BRANCO); isChecked = share
            setOnCheckedChangeListener { button, checked ->
                if (checked) AlertDialog.Builder(this@MapRadioActivity).setTitle("Compartilhar localização?")
                    .setMessage("Sua posição recente ficará visível a usuários autenticados desta sala enquanto estiver Online nesta tela. Você pode desligar a qualquer momento.")
                    .setPositiveButton("Compartilhar") { _, _ -> share = true; preferences.edit().putBoolean("share_location", true).apply(); publishPresence() }
                    .setNegativeButton("Cancelar") { _, _ -> button.isChecked = false }.show()
                else { share = false; preferences.edit().putBoolean("share_location", false).apply(); publishPresence() }
            }
        }
        list.addView(sharing, ui.mp(ui.dp(10)))
        list.addView(Switch(this).apply {
            text = "Orientação por voz"; textSize = 13f; setTextColor(Cores.BRANCO); isChecked = voiceEnabled
            setOnCheckedChangeListener { _, enabled ->
                voiceEnabled = enabled; preferences.edit().putBoolean("navigation_voice", enabled).apply()
                if (!enabled) navigationSpeech?.stop()
            }
        }, ui.mp(ui.dp(5)))
        list.addView(ui.botao("Meu veículo e privacidade", Ui.Estilo.TEXTO) { DriverProfileEditor.show(this, controls.room) { publishPresence() } }.apply { textSize = 13f })
        list.addView(ui.botao("Configurar serviço de rotas", Ui.Estilo.TEXTO) { routeSettings() }.apply { textSize = 12f })
        list.addView(ui.botao("Histórico de alertas e SOS", Ui.Estilo.CONTORNO) { eventHistory() }.apply { textSize = 13f })
        box.addView(ScrollView(this).apply { addView(list); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        info = ui.text("Busque um destino ou selecione no mapa", 12f, Cores.BRANCO_70).apply { maxLines = 3 }
        box.addView(info, ui.mp(ui.dp(8)))
        val actions = ui.hbox()
        actions.addView(ui.botao("Iniciar navegação", Ui.Estilo.CHEIO_VIOLETA) { if (route != null) {
            navigating = true; followVehicle = true
            currentFix()?.let(::onLocationChanged) ?: run { guidance.text = "Navegação aguardando uma posição GPS atual"; startGps() }
        } else calculateRoute() }, ui.peso(1f))
        actions.addView(ui.botao("Encerrar", Ui.Estilo.TEXTO) { stopNavigation() }, ui.peso(1f, ui.dp(5)))
        box.addView(actions, ui.mp(ui.dp(5))); renderRecent()
        return box
    }

    private fun renderRecent() {
        recentRows.removeAllViews()
        val destinations = (navigation.favorites() + navigation.recent()).distinctBy { it.id }.take(8)
        if (destinations.isEmpty()) recentRows.addView(ui.text("Suas buscas aparecerão aqui", 12f, Cores.BRANCO_70))
        destinations.forEach { item -> recentRows.addView(ui.text("⌖  ${item.name}", 13f, Cores.BRANCO).apply {
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10)); background = ui.fundo(Cores.ELEVADA, raio = 12f)
            maxLines = 2; setOnClickListener { selectDestination(item) }; setOnLongClickListener { saveFavorite(item); true }
        }, ui.mp(ui.dp(5))) }
    }
    private fun openFavorite(id: String) {
        val existing = when (id) { "home" -> navigation.home(); "work" -> navigation.work(); else -> null }
        if (existing != null) selectDestination(existing)
        else if (id == "favorites") {
            val items = navigation.favorites()
            AlertDialog.Builder(this).setTitle("Favoritos").setItems((items.map { it.name } + "Salvar destino atual").toTypedArray()) { _, index ->
                if (index < items.size) selectDestination(items[index]) else target?.let(::saveFavorite) ?: run { info.text = "Escolha um destino para salvar" }
            }.setNegativeButton("Voltar", null).show()
        } else {
            pendingFavorite = id
            info.text = "Busque o endereço de ${if (id == "home") "Casa" else "Trabalho"} para cadastrá-lo"
            search.requestFocus()
        }
    }
    private fun saveFavorite(value: SavedDestination) {
        if (targetEphemeral && target?.id == value.id || value.id.startsWith("driver:") || value.id.startsWith("sos:")) {
            info.text = "Posições compartilhadas são temporárias e não podem ser salvas como favoritas"; return
        }
        val name = EditText(this).apply { setText(value.name) }
        AlertDialog.Builder(this).setTitle("Salvar favorito").setView(name).setPositiveButton("Salvar") { _, _ ->
            navigation.saveFavorite(value.copy(id = UUID.randomUUID().toString(), name = name.text.toString().ifBlank { value.name })); renderRecent()
        }.setNegativeButton("Cancelar", null).show()
    }
    private fun searchAddress() {
        val query = search.text.toString().trim()
        if (query.length < 3) { info.text = "Digite um endereço com pelo menos três caracteres"; return }
        autocompleteJob?.cancel()
        info.text = "Buscando endereço…"
        lifecycleScope.launch {
            try {
                val results = navigation.search(if (query.contains("Valadares", true)) query else "$query, Governador Valadares, MG")
                if (results.isEmpty()) { info.text = "Nenhum endereço encontrado. Tente rua, número e cidade."; return@launch }
                AlertDialog.Builder(this@MapRadioActivity).setTitle("Escolha o destino")
                    .setItems(results.map { it.name }.toTypedArray()) { _, index -> selectDestination(results[index]); calculateRoute() }
                    .setNegativeButton("Cancelar", null).show()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { info.text = e.message ?: "Não foi possível buscar o endereço" }
        }
    }
    private fun selectDestination(value: SavedDestination) = selectDestination(value, false, null)
    private fun selectDestination(value: SavedDestination, ephemeral: Boolean, sourceKey: String?) {
        routeJob?.cancel(); route = null; navigating = false; navigationSpeech?.stop(); replaceLayer(Layer.ROUTE, emptyList())
        target = value; targetEphemeral = ephemeral; targetSourceKey = sourceKey
        suppressAutocomplete = true
        search.setText(value.name, false)
        suppressAutocomplete = false
        if (!ephemeral) {
            navigation.remember(value)
            pendingFavorite?.let { id -> navigation.saveFavorite(value.copy(id = id)) }
        }
        pendingFavorite = null
        renderRecent(); info.text = value.name
        map.overlays.filterIsInstance<Marker>().filter { it.id == "destination" }.forEach { map.overlays.remove(it) }
        map.overlays.add(Marker(map).apply { id = "destination"; position = value.point; title = value.name; icon = markerIcon(Cores.MAGENTA, "⌖") })
        map.controller.animateTo(value.point); map.invalidate()
        guidance.text = "Destino selecionado · toque em Mostrar rota"
    }
    private fun calculateRoute() {
        pruneSensitiveDestinations()
        val destination = target ?: run { searchAddress(); return }
        val origin = currentFix()?.let { GeoPoint(it.latitude, it.longitude) } ?: manualOrigin
        if (origin == null) {
            AlertDialog.Builder(this).setTitle("Escolha a origem")
                .setMessage("Ainda não há uma posição recente do GPS. Ative a localização ou use explicitamente o centro visível do mapa como origem.")
                .setPositiveButton("Usar centro do mapa") { _, _ -> manualOrigin = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude); calculateRoute() }
                .setNeutralButton("Ativar GPS") { _, _ -> permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                .setNegativeButton("Cancelar", null).show(); return
        }
        val endpoint = navigation.routingEndpoint().ifBlank { BuildConfig.ROUTING_BASE_URL }
        if (endpoint.isBlank()) { routeSettings(); return }
        routeJob?.cancel(); info.text = "Calculando rota…"
        routeJob = lifecycleScope.launch {
            try {
                val computed = RouteEngine(endpoint).route(origin, destination.point)
                route = computed
                replaceLayer(Layer.ROUTE, listOf(Polyline().apply { setPoints(computed.points); outlinePaint.color = Cores.CIANO; outlinePaint.strokeWidth = ui.dpf(5f); outlinePaint.strokeCap = Paint.Cap.ROUND }))
                val label = "${(computed.seconds / 60).toInt().coerceAtLeast(1)} min estimados  ·  ${"%.1f".format(computed.meters / 1000)} km"
                info.text = label; guidance.text = "$label\n${computed.steps.firstOrNull()?.text.orEmpty()}"
                guidance.setOnClickListener(null)
                map.post { runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(computed.points), true, ui.dp(80)) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { info.text = e.message ?: "Serviço de rota indisponível"; guidance.text = "Rota não calculada · toque para configurar"; guidance.setOnClickListener { routeSettings() } }
        }
    }
    private fun routeSettings() {
        val endpoint = EditText(this).apply { hint = "https://servidor-autorizado"; setText(navigation.routingEndpoint().ifBlank { BuildConfig.ROUTING_BASE_URL }); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val box = ui.vbox(ui.dp(16)).apply {
            addView(ui.text("Informe o serviço OSRM autorizado para sua conta. Não coloque senha ou chave secreta na URL.", 14f, Cores.BRANCO_70)); addView(endpoint)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Serviço de navegação").setView(box).setPositiveButton("Salvar e calcular", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { navigation.setRoutingEndpoint(endpoint.text.toString()) }.onSuccess {
                dialog.dismiss(); if (target != null && endpoint.text.isNotBlank()) calculateRoute()
            }.onFailure { endpoint.error = it.message }
        } }; dialog.show()
    }
    private fun stopNavigation() { routeJob?.cancel(); navigationSpeech?.stop(); route = null; navigating = false; replaceLayer(Layer.ROUTE, emptyList()); guidance.text = "Navegação encerrada"; info.text = "Escolha outro destino" }
    private fun followSettings() {
        AlertDialog.Builder(this).setTitle("Orientação do mapa")
            .setItems(arrayOf("Seguir veículo · norte acima", "Seguir direção do veículo", "Explorar sem recentralizar")) { _, index ->
                followVehicle = index < 2; headingUp = index == 1
                preferences.edit().putBoolean("heading_up", headingUp).apply()
                if (!headingUp) map.mapOrientation = 0f
                if (followVehicle) fix?.let(::onLocationChanged)
            }.setNegativeButton("Voltar", null).show()
    }
    private fun layersDialog() {
        val values = Layer.entries.toTypedArray()
        AlertDialog.Builder(this).setTitle("Camadas do mapa").setMultiChoiceItems(values.map { it.label }.toTypedArray(), values.map { visible[it] == true }.toBooleanArray()) { _, index, checked ->
            val layer = values[index]; visible[layer] = checked
            overlays.getValue(layer).forEach { if (checked) { if (!map.overlays.contains(it)) map.overlays.add(it) } else map.overlays.remove(it) }
            map.invalidate()
        }.setPositiveButton("Aplicar", null).show()
    }
    private fun replaceLayer(layer: Layer, next: List<Overlay>) {
        map.overlays.removeAll(overlays.getValue(layer).toSet()); overlays.getValue(layer).clear(); overlays.getValue(layer).addAll(next)
        if (visible[layer] == true) map.overlays.addAll(next); map.invalidate()
    }
    private fun renderPartners() {
        Layer.entries.filter { it == Layer.CHARGING || it == Layer.PARTNERS }.forEach { layer ->
            replaceLayer(layer, PartnerCatalog.partners.filter { it.hasCoordinates && (it.category == PartnerCategory.CHARGING) == (layer == Layer.CHARGING) }.map { partner ->
                Marker(map).apply { position = GeoPoint(partner.latitude!!, partner.longitude!!); title = partner.name; snippet = "${partner.statusLabel}\n${partner.address}"; icon = markerIcon(if (layer == Layer.CHARGING) Cores.BRANCO_70 else Cores.MAGENTA, if (layer == Layer.CHARGING) "ϟ" else "P")
                    setOnMarkerClickListener { _, _ -> AlertDialog.Builder(this@MapRadioActivity).setTitle(partner.name).setMessage("${partner.address}\n${partner.statusLabel}").setPositiveButton("Traçar rota") { _, _ -> partnerDestination(partner); calculateRoute() }.setNegativeButton("Fechar", null).show(); true }
                }
            })
        }
    }
    private fun partnerDestination(partner: Partner) {
        if (partner.hasCoordinates) selectDestination(SavedDestination(partner.id, partner.name, partner.latitude!!, partner.longitude!!))
        else partner.address?.let { search.setText(it); searchAddress() } ?: run { info.text = "Endereço ainda não confirmado pelo estabelecimento" }
    }
    private fun renderDrivers(drivers: List<DriverPresence>) {
        val current = drivers.filter { it.visibleOnMap(System.currentTimeMillis()) }
        activeDriverPositions.clear(); current.forEach { activeDriverPositions["driver:${it.uid}"] = it }
        pruneSensitiveDestinations()
        replaceLayer(Layer.DRIVERS, current.map { driver -> Marker(map).apply {
            position = GeoPoint(driver.latitude!!, driver.longitude!!); title = driver.name
            snippet = listOfNotNull(driver.vehicleModel, driver.vehicleColor, driver.licensePlate, "Online · precisão ${driver.accuracyMeters?.toInt()} m").filter { it.isNotBlank() }.joinToString(" · ")
            icon = VehicleMarkerDrawable.create(this@MapRadioActivity, driver.vehicleColor, driver.bearingDegrees?.toFloat())
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                AlertDialog.Builder(this@MapRadioActivity).setTitle(driver.name).setMessage(snippet)
                    .setPositiveButton("Ir até motorista") { _, _ ->
                        val currentDriver = activeDriverPositions["driver:${driver.uid}"]?.takeIf { it.visibleOnMap(System.currentTimeMillis()) }
                        if (currentDriver != null) {
                            selectDestination(SavedDestination("driver:${currentDriver.uid}", currentDriver.name, currentDriver.latitude!!, currentDriver.longitude!!), true, "driver:${currentDriver.uid}"); calculateRoute()
                        } else showError(IllegalStateException("A posição do motorista expirou. Aguarde atualização."))
                    }.setNegativeButton("Voltar", null).show(); true
            }
        } })
        if (::layerLabel.isInitialized) layerLabel.text = "Motoristas ${current.size}  ·  Eletropostos ${PartnerCatalog.partners.count { it.category == PartnerCategory.CHARGING }}"
    }
    private fun markerIcon(color: Int, label: String): android.graphics.drawable.Drawable {
        val size = ui.dp(46); val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }; val mid = size / 2f
        canvas.drawCircle(mid, mid * .82f, mid * .75f, paint)
        canvas.drawPath(Path().apply { moveTo(mid * .65f, mid); lineTo(mid, size.toFloat()); lineTo(mid * 1.35f, mid); close() }, paint)
        paint.color = Cores.OBSIDIAN; paint.textSize = ui.dpf(19f); paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, mid, mid * 1.1f, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun subscribeRoom() {
        observers?.cancel(); presence?.stop()
        renderEvents(emptyList(), true)
        activeDriverPositions.clear(); pruneSensitiveDestinations()
        val repo = community ?: return
        if (Conta.uid() == null) return
        val session = presence ?: PresenceSession(repo, lifecycleScope).also { presence = it }
        session.start(controls.room, { presenceUpdate() }) { roomPanel.status.text = RoomPanel.communityError(it) }
        observers = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { repo.observeAlerts(controls.room).catch { roomPanel.status.text = RoomPanel.communityError(it) }.collect { renderEvents(it, false) } }
            launch { repo.observeSos(controls.room).catch { renderEvents(emptyList(), true); roomPanel.status.text = RoomPanel.communityError(it) }.collect { renderEvents(it, true) } }
        } }
    }
    private fun presenceUpdate(): PresenceSession.Update {
        val recent = currentFix()
        return PresenceSession.Update(controls.status, share && recent != null, recent?.latitude, recent?.longitude,
            profile = runCatching { DriverProfileRepository(this).cached() }.getOrDefault(DriverProfile()),
            accuracyMeters = recent?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            bearingDegrees = recent?.takeIf { it.hasBearing() }?.bearing?.let(VehicleMarkerAppearance::bearing)?.toDouble(), locationTimestamp = recent?.time)
    }
    private fun publishPresence() {
        if (!::controls.isInitialized || Conta.uid() == null) return
        presence?.publishNow()
    }
    private val alertNames = linkedMapOf(AlertCategory.TRANSITO_LENTO to "Trânsito lento", AlertCategory.TRANSITO_PARADO to "Trânsito parado", AlertCategory.ACIDENTE to "Acidente", AlertCategory.OBRAS to "Obras", AlertCategory.BURACO to "Buraco", AlertCategory.ALAGAMENTO to "Alagamento", AlertCategory.VIA_BLOQUEADA to "Via bloqueada", AlertCategory.OBJETO to "Objeto na pista", AlertCategory.VEICULO_PARADO to "Veículo parado", AlertCategory.OUTRO to "Outro perigo")
    private fun report() {
        if (freshFix() == null) return
        AlertDialog.Builder(this).setTitle("Reportar perigo na posição atual").setItems(alertNames.values.toTypedArray()) { _, index ->
            val category = alertNames.keys.elementAt(index)
            val description = EditText(this).apply { hint = "Descrição opcional"; maxLines = 3 }
            AlertDialog.Builder(this).setTitle(alertNames[category]).setView(description).setPositiveButton("Enviar à sala") { _, _ ->
                val location = freshFix() ?: return@setPositiveButton
                val id = UUID.randomUUID().toString()
                lifecycleScope.launch { runCatching { checkNotNull(community).reportAlert(controls.room, category, location.latitude, location.longitude, description.text.toString().take(500), id) }
                    .onSuccess { Toast.makeText(this@MapRadioActivity, "Alerta registrado e enviado à sala", Toast.LENGTH_LONG).show() }.onFailure { showError(it) } }
            }.setNegativeButton("Cancelar", null).show()
        }.setNegativeButton("Cancelar", null).show()
    }
    private fun sos() {
        val account = Conta.uid() ?: run { controls.ensurePermission(); return }
        lifecycleScope.launch {
            try {
                val repo = checkNotNull(community) { "Conecte-se para verificar a disponibilidade do SOS global" }
                val eligibility = repo.globalSosEligibility()
                check(Conta.uid() == account && eligibility.enabled && CommunityRooms.slug(controls.room) in eligibility.rooms) {
                    eligibility.reason ?: "SOS global indisponível para esta conta nesta sala"
                }
                AlertDialog.Builder(this@MapRadioActivity).setTitle("Emergência — SOS global")
                    .setMessage("Ao confirmar, seu nome, modelo, cor do veículo e posição GPS atual poderão ser vistos por motoristas autenticados e habilitados da comunidade nas quatro salas: Geely, BYD, ROTA GV e Combustão. Sua placa não será compartilhada. Isso não aciona atendimento oficial automaticamente.")
                    .setPositiveButton("Compartilhar SOS global") { _, _ ->
                        val location = freshFix()?.let(::Location) ?: return@setPositiveButton
                        val hadFineAtConfirmation = hasFineLocation()
                        val profile = runCatching { DriverProfileRepository(this@MapRadioActivity).cached().validated() }.getOrElse { showError(it); return@setPositiveButton }
                        val sourceRoom = controls.room
                        val id = UUID.randomUUID().toString()
                        lifecycleScope.launch {
                            try {
                                val latest = repo.globalSosEligibility()
                                check(Conta.uid() == account && latest.enabled && CommunityRooms.slug(sourceRoom) in latest.rooms) { latest.reason ?: "SOS global não autorizado nesta sala" }
                                check(validFix(location) && (!hadFineAtConfirmation || hasFineLocation())) { "A posição GPS expirou ou sua permissão mudou. Atualize a posição e confirme novamente." }
                                lastSosId = repo.createSos(sourceRoom, location.latitude, location.longitude, "Pedido de ajuda", id, profile = profile)
                                Toast.makeText(this@MapRadioActivity, "SOS global registrado para a comunidade habilitada", Toast.LENGTH_LONG).show()
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { showError(error) }
                        }
                    }.setNeutralButton("Ligar 190") { _, _ -> startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:190"))) }
                    .setNegativeButton("Cancelar", null).show()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { showError(error) }
        }
    }
    private fun renderEvents(events: List<CommunityEvent>, sos: Boolean) {
        val display = if (sos) events.filter { it.valid(System.currentTimeMillis()) && NavigationRepository.validPoint(it.latitude, it.longitude) } else events
        if (sos) {
            activeSosEvents.clear(); display.forEach { activeSosEvents[sosKey(it)] = it }
            if (sosDialogKey?.let { activeSosEvents[it] == null } == true) { sosDialog?.dismiss(); sosDialog = null; sosDialogKey = null }
            pruneSensitiveDestinations()
        }
        val layer = if (sos) Layer.PARTNERS else Layer.ALERTS
        val markers = display.map { event -> Marker(map).apply {
            position = GeoPoint(event.latitude, event.longitude); title = if (sos) "SOS · pedido de ajuda" else alertNames.entries.firstOrNull { it.key.name == event.category }?.value ?: "Alerta"
            val color = if (sos || event.category == AlertCategory.TRANSITO_PARADO.name) Cores.CORAL else Cores.LARANJA
            icon = markerIcon(color, if (sos) "!" else "⚠")
            setOnMarkerClickListener { _, _ -> eventDetails(event, sos); true }
        } }
        if (sos) { sosMarkers.forEach { map.overlays.remove(it) }; sosMarkers.clear(); sosMarkers.addAll(markers); map.overlays.addAll(markers); map.invalidate() }
        else {
            replaceLayer(layer, markers.filterIndexed { index, _ -> events[index].category !in listOf("TRANSITO_LENTO", "TRANSITO_PARADO") })
            replaceLayer(Layer.TRAFFIC, markers.filterIndexed { index, _ -> events[index].category in listOf("TRANSITO_LENTO", "TRANSITO_PARADO") })
        }
    }
    private val sosMarkers = mutableListOf<Overlay>()
    private fun sosKey(event: CommunityEvent) = "sos:${event.global}:${event.id}"
    private fun activeSos(key: String): CommunityEvent? = activeSosEvents[key]?.takeIf { it.valid(System.currentTimeMillis()) }
    private fun clearEphemeralDestination() {
        if (!targetEphemeral) return
        stopNavigation(); target = null; targetEphemeral = false; targetSourceKey = null; pendingFavorite = null
        search.setText("", false)
        map.overlays.removeAll { it is Marker && it.id == "destination" }
        guidance.text = "Posição compartilhada encerrada, expirada ou indisponível"
        map.invalidate()
    }
    private fun pruneSensitiveDestinations() {
        if (!targetEphemeral) return
        val key = targetSourceKey
        val valid = when {
            key?.startsWith("sos:") == true -> activeSos(key) != null
            key?.startsWith("driver:") == true -> activeDriverPositions[key]?.visibleOnMap(System.currentTimeMillis()) == true
            else -> false
        }
        if (!valid) clearEphemeralDestination()
    }
    private fun eventDetails(event: CommunityEvent, sos: Boolean) {
        val builder = AlertDialog.Builder(this).setTitle(if (sos) "SOS" else "Alerta reportado")
            .setMessage("${event.description}\n${if (event.active && event.expiresAt > System.currentTimeMillis()) "Ativo" else "Encerrado ou expirado"}\nRelato de usuário, sujeito a confirmação.")
            .setPositiveButton("Rota até o local") { _, _ ->
                val current = if (sos) activeSos(sosKey(event)) else event.takeIf { it.valid(System.currentTimeMillis()) }
                if (current == null) { showError(IllegalStateException("O evento encerrou, expirou ou seu acesso foi revogado. A posição não será reutilizada.")); return@setPositiveButton }
                selectDestination(SavedDestination(if (sos) sosKey(current) else current.id, if (sos) "SOS" else "Alerta", current.latitude, current.longitude), sos, if (sos) sosKey(current) else null)
                calculateRoute()
            }
        if (event.authorUid == Conta.uid()) builder.setNeutralButton("Encerrar") { _, _ -> lifecycleScope.launch { runCatching { if (sos) community?.closeSos(event.sourceRoom ?: controls.room, event.id) else community?.closeAlert(controls.room, event.id) }.onFailure { showError(it) } } }
        else if (!sos) builder.setNeutralButton("Confirmar / contestar") { _, _ ->
            AlertDialog.Builder(this).setTitle("Situação do alerta").setItems(arrayOf("Confirmar ocorrência", "Não encontrei essa ocorrência")) { _, index -> lifecycleScope.launch { runCatching { community?.voteAlert(controls.room, event.id, index == 0) }.onFailure { showError(it) } } }.show()
        }
        val dialog = builder.setNegativeButton("Fechar", null).create()
        if (sos) { sosDialog?.dismiss(); sosDialog = dialog; sosDialogKey = sosKey(event) }
        dialog.show()
    }
    private fun eventHistory() { lifecycleScope.launch {
        runCatching { checkNotNull(community).eventHistory(controls.room, false) + checkNotNull(community).eventHistory(controls.room, true) }.onSuccess { events ->
            AlertDialog.Builder(this@MapRadioActivity).setTitle("Histórico de alertas e SOS").setItems(events.map { "${it.category} · ${if (it.valid(System.currentTimeMillis())) "Ativo" else "Encerrado"}" }.toTypedArray()) { _, index -> eventDetails(events[index], events[index].category == "SOS") }.setNegativeButton("Fechar", null).show()
        }.onFailure { showError(it) }
    } }
    private fun freshFix(): Location? {
        if (Conta.uid() == null) { controls.ensurePermission(); return null }
        expireGpsFix()
        val current = currentFix()
        if (current == null) { Toast.makeText(this, "É necessária uma posição GPS recente para compartilhar", Toast.LENGTH_LONG).show(); if (hasLocation()) startGps() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        return current
    }
    private fun showError(error: Throwable) { AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(RoomPanel.communityError(error)).setPositiveButton("Voltar", null).show() }
    private fun navigate(page: String) { when (page) {
        "inicio" -> startActivity(Intent(this, space.neural.luna.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        "mapa" -> Unit
        "motoristas" -> sheet("Participantes", roomPanel.view)
        "eletropostos", "parceiros" -> startActivity(Intent(this, ParceirosActivity::class.java))
        "alertas" -> AlertDialog.Builder(this).setTitle("Alertas e SOS").setItems(arrayOf("Reportar perigo", "SOS", "Histórico")) { _, index -> when (index) { 0 -> report(); 1 -> sos(); else -> eventHistory() } }.show()
        "rotas" -> sheet("Rota no mapa", routePanel)
        "configuracoes" -> startActivity(Intent(this, ConfiguracoesActivity::class.java))
    } }
    private fun sheet(title: String, view: View) {
        if (automotiveProfile.mapSidePanels) { view.requestFocus(); return }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(view).setNegativeButton("Voltar ao mapa", null).create()
        dialog.show(); dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .8f).toInt())
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasFineLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun currentFix(): Location? = fix?.takeIf { validFix(it) && (!fixReceivedWithFinePermission || hasFineLocation()) }
    private fun validFix(location: Location): Boolean = LocationFreshness.usable(hasLocation(), location.time, location.latitude, location.longitude,
        location.takeIf { it.hasAccuracy() }?.accuracy)
    @Suppress("MissingPermission") private fun startGps() {
        expireGpsFix()
        if (!hasLocation()) return
        val fine = hasFineLocation()
        val providers = listOf(LocationManager.NETWORK_PROVIDER) + if (fine) listOf(LocationManager.GPS_PROVIDER) else emptyList()
        var enabled = false
        providers.forEach { provider -> runCatching {
            if (locations.isProviderEnabled(provider)) { enabled = true; locations.requestLocationUpdates(provider, 3000L, 5f, this); locations.getLastKnownLocation(provider)?.takeIf { LocationFreshness.isCurrent(it.time) }?.let(::onLocationChanged) }
        } }
        if (!enabled) gps.text = "◎ Localização desativada no dispositivo" else if (fix == null) gps.text = "◎ Buscando posição…"
    }
    override fun onLocationChanged(location: Location) {
        pruneSensitiveDestinations()
        if (!hasLocation()) { expireGpsFix(); return }
        if (fixReceivedWithFinePermission && !hasFineLocation()) expireGpsFix()
        if (location.provider == LocationManager.GPS_PROVIDER && !hasFineLocation()) return
        if (location.time < minimumAuthorizedFixTime || !validFix(location) || !LocationFreshness.notOlderThan(location.time, fix?.time)) return
        fix = Location(location)
        fixReceivedWithFinePermission = hasFineLocation()
        if (share && System.currentTimeMillis() - lastPresenceFix >= 10_000) {
            lastPresenceFix = System.currentTimeMillis(); publishPresence()
        }
        val point = GeoPoint(location.latitude, location.longitude)
        if (ownMarker == null) ownMarker = Marker(map).also { it.title = "Sua posição"; it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); map.overlays.add(it) }
        ownMarker?.position = point; gps.text = if (location.hasAccuracy()) "◎ GPS · precisão ${location.accuracy.toInt()} m" else "◎ GPS · precisão não informada"
        val profile = runCatching { DriverProfileRepository(this).cached() }.getOrDefault(DriverProfile())
        val bearing = location.takeIf { it.hasBearing() }?.bearing?.let(VehicleMarkerAppearance::bearing)
        ownMarker?.icon = VehicleMarkerDrawable.create(this, profile.vehicleColor, bearing, profile.vehicleIcon)
        ownMarker?.rotation = 0f
        if (followVehicle && headingUp && bearing != null) map.mapOrientation = -bearing
        if (firstFix || followVehicle && navigating) { map.controller.animateTo(point); firstFix = false }
        if (navigating) route?.let { active -> target?.let { end ->
            val progress = RouteProgress.at(active, point, end.point)
            val maneuverDistance = progress.nextManeuverMeters?.let { if (it < 1000) "Em ${it.toInt()} m" else "Em ${"%.1f".format(it / 1000)} km" }.orEmpty()
            val arrow = when (progress.maneuverModifier) { "left", "slight left", "sharp left" -> "↰"; "right", "slight right", "sharp right" -> "↱"; "uturn" -> "↶"; else -> "↑" }
            val eta = java.time.LocalTime.now().plusSeconds(progress.remainingSeconds.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            guidance.text = "$arrow $maneuverDistance · ${progress.instruction}\n${"%.1f".format(progress.remainingMeters / 1000)} km restantes · ${(progress.remainingSeconds / 60).toInt().coerceAtLeast(1)} min\nChegada estimada $eta · sem trânsito ao vivo"
            if (voiceEnabled && !controls.manager.talking.value && controls.manager.activeSpeakers.value.none { !it.local }) {
                if (navigationSpeech == null) navigationSpeech = NavigationSpeechController(this) { state -> info.text = state }
                navigationSpeech?.instruction(progress.instruction)
            }
            if (progress.arrived) { navigating = false; guidance.text = "Você chegou ao destino" }
            else if (progress.offRoute && System.currentTimeMillis() - lastReroute > 30_000 && routeJob?.isActive != true) { lastReroute = System.currentTimeMillis(); calculateRoute() }
        } }
        map.invalidate()
        val action = pendingAction; pendingAction = null
        if (action == "REPORT") report() else if (action == "SOS") sos()
    }
    override fun onStart() { super.onStart(); if (::roomPanel.isInitialized) subscribeRoom() }
    private fun expireGpsFix() {
        if (!hasLocation()) {
            invalidateGpsFix("◎ Localização sem permissão · toque para autorizar")
            runCatching { locations.removeUpdates(this) }
            return
        }
        val current = fix ?: return
        if (fixReceivedWithFinePermission && !hasFineLocation()) {
            minimumAuthorizedFixTime = System.currentTimeMillis()
            invalidateGpsFix("◎ Precisão aproximada · aguardando nova posição autorizada")
            return
        }
        if (validFix(current)) return
        invalidateGpsFix("◎ GPS desatualizado · aguardando nova posição")
    }
    private fun invalidateGpsFix(message: String) {
        val previouslyHadFix = fix != null
        fix = null
        fixReceivedWithFinePermission = false
        ownMarker?.let { map.overlays.remove(it) }; ownMarker = null
        firstFix = true
        gps.text = message
        if (navigating) {
            navigationSpeech?.stop()
            guidance.text = "Navegação aguardando uma posição GPS atual"
        }
        if (share && previouslyHadFix) publishPresence()
        map.invalidate()
    }
    override fun onProviderDisabled(provider: String) {
        if (fix?.provider == provider) invalidateGpsFix("◎ Provedor de localização desativado · aguardando posição")
        startGps()
    }
    override fun onProviderEnabled(provider: String) { startGps() }
    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) {
            map.onResume(); expireGpsFix(); startGps()
            gpsFreshnessJob?.cancel()
            gpsFreshnessJob = lifecycleScope.launch {
                while (isActive) {
                    delay(5_000); expireGpsFix()
                    val current = activeSosEvents.values.filter { it.valid(System.currentTimeMillis()) }
                    if (current.size != activeSosEvents.size) renderEvents(current, true)
                    pruneSensitiveDestinations()
                }
            }
        }
    }
    override fun onPause() { gpsFreshnessJob?.cancel(); navigationSpeech?.stop(); if (::controls.isInitialized) controls.manager.cancelTransmission(); runCatching { locations.removeUpdates(this) }; if (::map.isInitialized) map.onPause(); super.onPause() }
    override fun onStop() { renderEvents(emptyList(), true); activeDriverPositions.clear(); pruneSensitiveDestinations(); presence?.stop(); observers?.cancel(); super.onStop() }
    override fun onDestroy() { navigationSpeech?.close(); routeJob?.cancel(); if (::map.isInitialized) map.onDetach(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        if (!targetEphemeral) target?.let { out.putString("destination_name", it.name); out.putDouble("destination_lat", it.latitude); out.putDouble("destination_lon", it.longitude) }
        else { out.remove("destination_name"); out.remove("destination_lat"); out.remove("destination_lon") }
        super.onSaveInstanceState(out)
    }
}
