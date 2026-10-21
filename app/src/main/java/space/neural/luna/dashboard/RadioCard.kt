package space.neural.luna.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.neural.luna.voice.VoiceRoomManager
import space.neural.luna.voice.HttpTokenProvider

/**
 * Card de Rádio dos Parceiros (LiveKit).
 * Reutiliza VoiceRoomManager e TokenProvider existentes.
 * Token vem de endpoint seguro (LIVEKIT_TOKEN_ENDPOINT). Nunca embute secret.
 */
class RadioCard(private val ui: Ui, private val activity: androidx.appcompat.app.AppCompatActivity) {

    val view: View
    private val status: TextView
    private val btnJoin: Button
    private val btnPtt: Button
    private val btnLeave: Button
    private val manager by lazy { VoiceRoomManager.get(activity) }
    private val talkMutex = Mutex()
    private var talking = false

    init {
        val root = ui.vbox().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        root.addView(ui.text("Rádio dos Parceiros", 16f, Cores.BRANCO).apply { setPadding(0, 0, 0, ui.dp(8)) })

        status = ui.text("Desconectado", 14f, Cores.BRANCO_70)
        root.addView(status)

        btnJoin = Button(activity).apply {
            text = "Entrar na sala"
            setOnClickListener { entrar() }
        }
        btnPtt = Button(activity).apply {
            text = "Apertar para falar"
            isEnabled = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        activity.lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.startTalking() }.onSuccess { talking = true }
                                    .onFailure { status.text = "Erro ao ativar microfone: ${it.message}" }
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        activity.lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.stopTalking() }.onSuccess { talking = false }
                                    .onFailure { status.text = "Erro ao desligar microfone: ${it.message}" }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        btnLeave = Button(activity).apply {
            text = "Sair da sala"
            isEnabled = false
            setOnClickListener { activity.lifecycleScope.launch { manager.leave() } }
        }

        root.addView(btnJoin, ui.mp(ui.dp(4)))
        root.addView(btnPtt, ui.mp(ui.dp(4)))
        root.addView(btnLeave, ui.mp(ui.dp(4)))

        view = root
        observarEstado()
    }

    private fun entrar() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            status.text = "Permissão de microfone necessária."
            return
        }
        activity.lifecycleScope.launch {
            runCatching {
                manager.join("rota-gv", "motorista-${System.currentTimeMillis() % 10000}", HttpTokenProvider())
            }.onFailure { status.text = "Erro ao entrar: ${it.message}" }
        }
    }

    private fun observarEstado() {
        activity.lifecycleScope.launch {
            manager.state.collect { s ->
                when (s) {
                    is VoiceRoomManager.State.Disconnected -> {
                        status.text = "Desconectado"
                        btnJoin.isEnabled = true
                        btnPtt.isEnabled = false
                        btnLeave.isEnabled = false
                    }
                    is VoiceRoomManager.State.Connecting -> status.text = "Conectando…"
                    is VoiceRoomManager.State.Connected -> {
                        status.text = "Conectado em ${s.roomName}"
                        btnJoin.isEnabled = false
                        btnPtt.isEnabled = true
                        btnLeave.isEnabled = true
                    }
                    is VoiceRoomManager.State.Error -> status.text = "Erro: ${s.message}"
                }
            }
        }
    }
}
