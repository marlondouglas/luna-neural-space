package space.neural.luna.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import space.neural.luna.automotive.FocusedPttKeyState
import space.neural.luna.R
import space.neural.luna.collaboration.CommunityRooms
import space.neural.luna.collaboration.DriverStatus
import space.neural.luna.conta.Conta
import space.neural.luna.conta.LoginActivity
import space.neural.luna.dashboard.*
import space.neural.luna.voice.VoiceRoomManager

/** One PTT/session control shared by both driving screens. SDK state is the source of truth. */
class DrivingControls(
    private val activity: AppCompatActivity,
    val currentPage: String,
    private val onRoom: (String) -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onReport: () -> Unit,
    private val onSos: () -> Unit,
    private val onPresence: (DriverStatus) -> Unit,
) {
    val ui = Ui(activity)
    val manager = VoiceRoomManager.get(activity)
    private val preferences = activity.getSharedPreferences("driving", 0)
    var room = (manager.state.value as? VoiceRoomManager.State.Connected)?.roomName
        ?: preferences.getString("room", "luna-eletricos-gv")!!
        private set
    var status = runCatching { DriverStatus.valueOf(preferences.getString("presence", "ONLINE")!!) }.getOrDefault(DriverStatus.ONLINE)
        private set
    private var blocked = false
    private val pttKeys = FocusedPttKeyState()
    private var pttKeyJob: Job? = null
    private val roomButtons = linkedMapOf<String, Button>()
    private var compactRoom: Button? = null
    val connection = ui.text("Rádio desconectado", 12f, Cores.BRANCO_70)
    private val connectionBadge = RotaDesign.badge(ui, "Conectar", Cores.MAGENTA)
    private val pttText = ui.text("Segure\npara falar", 12f, Cores.BRANCO, true).apply { gravity = Gravity.CENTER }
    private val pttIcon = ui.icone(R.drawable.ic_mic, Cores.BRANCO, 33)
    private val ptt = FrameLayout(activity)
    private val reception = ui.text("Sem transmissão", 12f, Cores.BRANCO_70)
    private val presenceButtons = mutableMapOf<DriverStatus, TextView>()
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) connect() else connection.text = "Microfone negado. Toque em Conectar para tentar novamente."
    }

    val header: View
    val audioBar: View
    val dock: View

    init {
        header = buildHeader()
        audioBar = buildAudio()
        dock = buildDock()
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { cancelPttKey(); manager.cancelTransmission() }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { renderConnection(it) } }
                launch { manager.participants.collect { renderRooms() } }
                launch { manager.talking.collect { speaking ->
                    pttText.text = if (speaking) "Transmitindo\nsolte para parar" else "Segure\npara falar"
                    ptt.background = RotaDesign.gradient(ui, if (speaking) Cores.MAGENTA else Cores.VIOLETA, if (speaking) Cores.CORAL else Cores.MAGENTA, 90f)
                } }
                launch { manager.activeSpeakers.collect { speakers ->
                    reception.text = speakers.filter { !it.local }.joinToString { "${it.name} falando" }.ifBlank { "Sem transmissão recebida" }
                    reception.setTextColor(if (speakers.any { !it.local }) Cores.ESMERALDA else Cores.BRANCO_70)
                } }
            }
        }
        renderRooms()
    }

    private fun buildHeader(): View {
        val compact = activity.resources.configuration.screenWidthDp < 600
        val row = ui.hbox().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = RotaDesign.panel(ui) }
        if (currentPage == "parceiros") {
            row.addView(ui.botao("‹", Ui.Estilo.TEXTO) { activity.finish() }.apply { textSize = 30f }, ui.wc())
            row.addView(RotaDesign.brand(ui), ui.wc(ui.dp(8)))
            row.addView(ui.text("Ofertas e parceiros", 18f, Cores.BRANCO, true), ui.peso(1f, ui.dp(8)))
            row.addView(RotaDesign.roundIcon(ui, R.drawable.ic_settings, "Configurações") { onNavigate("configuracoes") }, ui.wc())
            return row
        }
        row.addView(ui.botao("‹", Ui.Estilo.TEXTO) { activity.finish() }.apply { textSize = 30f }, ui.wc())
        row.addView(RotaDesign.brand(ui), ui.wc(ui.dp(2)))
        if (!compact) row.addView(ui.text(if (currentPage == "parceiros") "Parceiros" else "Rádio e Mapa", 19f, Cores.BRANCO, true), ui.wc(ui.dp(18)))
        if (compact) {
            val selector = ui.botao(CommunityRooms.labels[room] ?: room, Ui.Estilo.CONTORNO) {
                AlertDialog.Builder(activity).setTitle("Sala do rádio")
                    .setItems(CommunityRooms.labels.values.toTypedArray()) { _, index -> selectRoom(CommunityRooms.labels.keys.elementAt(index)) }.show()
            }.apply { contentDescription = "Escolher sala do rádio"; textSize = 13f }
            compactRoom = selector
            row.addView(selector, ui.wc(ui.dp(6)))
        }
        CommunityRooms.labels.forEach { (slug, label) ->
            val button = ui.botao(label, Ui.Estilo.CONTORNO) { selectRoom(slug) }.apply { textSize = 14f }
            roomButtons[slug] = button; if (!compact) row.addView(button, ui.wc(ui.dp(7)))
        }
        connectionBadge.setOnClickListener { if (manager.state.value is VoiceRoomManager.State.Connected) showAudioSettings() else ensurePermission() }
        val actions = if (compact) ui.hbox().apply { setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(4)) } else row
        actions.addView(connectionBadge, ui.wc(ui.dp(12)))
        actions.addView(RotaDesign.roundIcon(ui, R.drawable.ic_car, "Meu veículo e privacidade") { DriverProfileEditor.show(activity, room) { onPresence(status) } }, ui.wc(ui.dp(10)))
        actions.addView(RotaDesign.roundIcon(ui, R.drawable.ic_settings, "Configurações") { onNavigate("configuracoes") }, ui.wc(ui.dp(10)))
        if (compact) return ui.vbox().apply {
            background = RotaDesign.panel(ui)
            addView(RotaDesign.scrollRow(ui, row)); addView(actions)
        }
        return RotaDesign.scrollRow(ui, row)
    }

    private fun buildAudio(): View {
        val row = ui.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); background = RotaDesign.panel(ui)
        }
        val statuses = ui.vbox()
        val statusRow = ui.hbox()
        DriverStatus.entries.forEach { value ->
            val color = when (value) { DriverStatus.ONLINE -> Cores.ESMERALDA; DriverStatus.OCUPADO -> Cores.LARANJA; DriverStatus.OFFLINE -> Cores.BRANCO_70 }
            val label = when (value) { DriverStatus.ONLINE -> "Disponível"; DriverStatus.OCUPADO -> "Ocupado"; DriverStatus.OFFLINE -> "Offline" }
            val button = RotaDesign.badge(ui, "● $label", color).apply {
                minimumHeight = ui.dp(48); gravity = Gravity.CENTER
                setOnClickListener {
                    status = value; preferences.edit().putString("presence", value.name).apply()
                    onPresence(value); renderPresence()
                    if (value == DriverStatus.OFFLINE) manager.leave()
                }
            }
            presenceButtons[value] = button; statusRow.addView(button, ui.wc(ui.dp(4)))
        }
        statuses.addView(statusRow); statuses.addView(connection, ui.mp(ui.dp(4)))
        row.addView(statuses, if (activity.resources.configuration.screenWidthDp >= 840) LinearLayout.LayoutParams(ui.dp((activity.resources.configuration.screenWidthDp * .30f).toInt()), -2) else ui.wc())
        val pttSize = if (activity.resources.configuration.screenHeightDp < 500) 90 else 110
        ptt.background = RotaDesign.gradient(ui, radius = 90f)
        ptt.elevation = ui.dpf(12f)
        val content = ui.vbox().apply { gravity = Gravity.CENTER; addView(pttIcon); addView(pttText, ui.wc()) }
        ptt.addView(content, FrameLayout.LayoutParams(-1, -1))
        ptt.contentDescription = "Pressione e segure para transmitir. Solte para parar."
        ptt.isFocusable = true
        ptt.setOnFocusChangeListener { _, focused -> if (!focused) cancelPttKey() }
        ptt.setOnKeyListener { view, key, event ->
            val activation = key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!activation || !view.hasFocus()) return@setOnKeyListener false
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    val foreground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (pttKeys.press(key, event.repeatCount, view.hasFocus(), foreground, keyTransmissionReady() && pttKeyJob?.isActive != true)) {
                        pttKeyJob = activity.lifecycleScope.launch {
                            if (pttKeys.holds(key) && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && keyTransmissionReady()) {
                                runCatching { manager.startTalking() }.onFailure { connection.text = it.message }
                            }
                        }
                    } else if (foreground && event.repeatCount == 0 && !blocked && (manager.state.value !is VoiceRoomManager.State.Connected || ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) ensurePermission()
                    true
                }
                android.view.KeyEvent.ACTION_UP -> { if (pttKeys.release(key)) manager.cancelTransmission(); true }
                else -> { cancelPttKey(); true }
            }
        }
        ptt.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (manager.state.value !is VoiceRoomManager.State.Connected) ensurePermission()
                    else if (!blocked) activity.lifecycleScope.launch { runCatching { manager.startTalking() }.onFailure { connection.text = it.message } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { manager.cancelTransmission(); true }
                else -> true
            }
        }
        row.addView(ptt, LinearLayout.LayoutParams(ui.dp(pttSize), ui.dp(pttSize)).apply { leftMargin = ui.dp(18); rightMargin = ui.dp(18) })
        row.addView(ui.vbox().apply {
            addView(reception)
            addView(ui.botao("Na ligação · mudo", Ui.Estilo.CONTORNO) {
                blocked = !blocked; manager.cancelTransmission()
                (it as Button).text = if (blocked) "Microfone bloqueado" else "Na ligação · mudo"
            }, ui.mp(ui.dp(4)))
        })
        row.addView(ui.botao("Reportar", Ui.Estilo.CONTORNO) { onReport() }.apply {
            setTextColor(Cores.LARANJA); background = RotaDesign.panel(ui, Cores.LARANJA)
            ui.comIcone(this, R.drawable.ic_flag)
        }, ui.wc(ui.dp(12)))
        row.addView(ui.botao("SOS", Ui.Estilo.CONTORNO) { onSos() }.apply {
            setTextColor(Cores.CORAL); background = RotaDesign.panel(ui, Cores.CORAL)
        }, ui.wc(ui.dp(8)))
        row.addView(RotaDesign.roundIcon(ui, R.drawable.ic_volume, "Dispositivo de áudio") { showAudioSettings() }, ui.wc(ui.dp(8)))
        renderPresence()
        return RotaDesign.scrollRow(ui, row)
    }

    private fun buildDock(): View {
        val row = ui.hbox().apply { tag = "primary_navigation"; setPadding(ui.dp(6), ui.dp(5), ui.dp(6), ui.dp(5)); background = RotaDesign.panel(ui) }
        val entries = listOf(Triple("inicio", "Início", R.drawable.ic_bolt), Triple("mapa", "Mapa e Rádio", R.drawable.ic_map),
            Triple("parceiros", "Ofertas", R.drawable.ic_partners))
        entries.forEach { (id, label, icon) ->
            val compact = activity.resources.configuration.screenWidthDp < 600 || activity.resources.configuration.fontScale >= 1.5f
            val item = (if (compact) ui.vbox() else ui.hbox()).apply {
                minimumHeight = ui.dp(52); gravity = Gravity.CENTER
                setPadding(ui.dp(if (compact) 6 else 18), ui.dp(6), ui.dp(if (compact) 6 else 18), ui.dp(6))
                val color = if (currentPage == id) Cores.CIANO else Cores.BRANCO_70
                if (currentPage == id) background = RotaDesign.panel(ui)
                addView(ui.icone(icon, color, 26))
                addView(ui.ajustavel(label, 14f, 10f, color, currentPage == id).apply { gravity = Gravity.CENTER }, if (compact) ui.mp(ui.dp(3)) else ui.wc(ui.dp(8)))
                contentDescription = label; isFocusable = true; setOnClickListener { onNavigate(id) }
            }
            row.addView(item, ui.peso(1f, ui.dp(3)))
        }
        return row
    }

    fun selectRoom(slug: String) {
        if (room == slug && manager.state.value is VoiceRoomManager.State.Connected) return
        cancelPttKey(); manager.cancelTransmission(); manager.leave()
        room = CommunityRooms.slug(slug); preferences.edit().putString("room", room).apply()
        renderRooms(); onRoom(room); ensurePermission()
    }
    private fun keyTransmissionReady(): Boolean = !blocked && manager.state.value is VoiceRoomManager.State.Connected && !manager.talking.value && manager.activeSpeakers.value.none { !it.local } &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun cancelPttKey() {
        // Let an in-flight SDK enable finish under the manager's generation guard; do not cancel
        // its await before it can detect that this key was released and disable the publication.
        val held = pttKeys.cancel()
        if (held) manager.cancelTransmission()
    }

    fun ensurePermission() {
        if (Conta.uid() == null) {
            connection.text = "Entre na sua conta para usar o rádio e a comunidade"
            activity.startActivity(Intent(activity, LoginActivity::class.java)); return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        else connect()
    }

    private fun connect() {
        val uid = Conta.uid() ?: return
        activity.lifecycleScope.launch { manager.join(room, uid) }
    }
    private fun renderRooms() {
        compactRoom?.text = (CommunityRooms.labels[room] ?: room) + " ▾"
        roomButtons.forEach { (slug, button) ->
            val selected = slug == room
            button.text = CommunityRooms.labels[slug] + if (selected) " · ${manager.participants.value.size}" else ""
            button.background = if (selected) RotaDesign.gradient(ui, Cores.mix(Cores.OBSIDIAN, Cores.ESMERALDA, .2f), Cores.mix(Cores.SUPERFICIE, Cores.ESMERALDA, .12f), 28f)
                else RotaDesign.panel(ui)
            button.setTextColor(if (selected) Cores.ESMERALDA else Cores.BRANCO_70)
        }
    }
    private fun renderPresence() {
        presenceButtons.forEach { (value, view) ->
            view.alpha = if (value == status) 1f else .58f
            if (activity.resources.configuration.screenWidthDp < 600) {
                view.visibility = if (value == status) View.VISIBLE else View.GONE
                if (value == status) view.setOnClickListener {
                    AlertDialog.Builder(activity).setTitle("Sua disponibilidade").setItems(arrayOf("Disponível", "Ocupado", "Offline")) { _, index ->
                        status = DriverStatus.entries[index]; preferences.edit().putString("presence", status.name).apply(); onPresence(status); renderPresence()
                        if (status == DriverStatus.OFFLINE) manager.leave()
                    }.show()
                }
                connection.maxWidth = ui.dp(118)
            }
        }
    }
    private fun renderConnection(state: VoiceRoomManager.State) {
        val connected = state is VoiceRoomManager.State.Connected
        ptt.alpha = if (connected && !blocked) 1f else .6f
        connectionBadge.text = if (connected) "● AO VIVO" else "Conectar rádio"
        connection.text = when (state) {
            VoiceRoomManager.State.Disconnected -> "Rádio desconectado"
            is VoiceRoomManager.State.Connecting -> "Conectando / reconectando…"
            is VoiceRoomManager.State.Connected -> "Conectado · ${CommunityRooms.labels[state.roomName] ?: state.roomName}"
            is VoiceRoomManager.State.Error -> state.message
        }
        connection.maxLines = 2; renderRooms()
    }
    private fun showAudioSettings() {
        val devices = manager.availableAudioDevices()
        val labels = listOf("Automático") + devices
        AlertDialog.Builder(activity).setTitle("Saída de áudio")
            .setSingleChoiceItems(labels.toTypedArray(), labels.indexOf(manager.selectedAudioDevice()).coerceAtLeast(0)) { dialog, index ->
                if (!manager.selectAudioDevice(if (index == 0) null else devices[index - 1]))
                    Toast.makeText(activity, "Conecte o rádio para escolher um dispositivo disponível", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }.setNeutralButton("Sair da sala") { _, _ -> manager.leave() }.setNegativeButton("Voltar", null).show()
    }
}
