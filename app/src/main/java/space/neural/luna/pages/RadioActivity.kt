package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.voice.HttpTokenProvider
import space.neural.luna.voice.VoiceRoomManager

/**
 * Página RÁDIO DOS MOTORISTAS (LiveKit).
 * Separada do dashboard. Reutiliza VoiceRoomManager existente.
 */
class RadioActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var btnJoin: Button
    private lateinit var btnPtt: Button
    private lateinit var btnLeave: Button
    private val manager by lazy { VoiceRoomManager.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Rádio dos Motoristas")

        status = ui.text("Desconectado", 16f, Cores.BRANCO)
        c.addView(status, ui.mp(ui.dp(8)))

        btnJoin = Button(this).apply { text = "Entrar na sala"; setOnClickListener { entrar() } }
        btnPtt = Button(this).apply {
            text = "Apertar para falar"
            isEnabled = false
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { manager.startTalking(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { manager.stopTalking(); true }
                    else -> false
                }
            }
        }
        btnLeave = Button(this).apply {
            text = "Sair da sala"
            isEnabled = false
            setOnClickListener { lifecycleScope.launch { manager.leave() } }
        }
        c.addView(btnJoin, ui.mp(ui.dp(6)))
        c.addView(btnPtt, ui.mp(ui.dp(6)))
        c.addView(btnLeave, ui.mp(ui.dp(6)))

        setContentView(raiz)
        observar()
    }

    private fun entrar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            status.text = "Permissão de microfone necessária."
            return
        }
        lifecycleScope.launch {
            runCatching {
                manager.join("rota-gv", "motorista-${System.currentTimeMillis() % 10000}", HttpTokenProvider())
            }.onFailure { status.text = "Erro ao entrar: ${it.message}" }
        }
    }

    private fun observar() {
        lifecycleScope.launch {
            manager.state.collect { s ->
                when (s) {
                    is VoiceRoomManager.State.Disconnected -> {
                        status.text = "Desconectado"
                        btnJoin.isEnabled = true; btnPtt.isEnabled = false; btnLeave.isEnabled = false
                    }
                    is VoiceRoomManager.State.Connecting -> status.text = "Conectando…"
                    is VoiceRoomManager.State.Connected -> {
                        status.text = "Conectado em ${s.roomName}"
                        btnJoin.isEnabled = false; btnPtt.isEnabled = true; btnLeave.isEnabled = true
                    }
                    is VoiceRoomManager.State.Error -> status.text = "Erro: ${s.message}"
                }
            }
        }
    }
}
